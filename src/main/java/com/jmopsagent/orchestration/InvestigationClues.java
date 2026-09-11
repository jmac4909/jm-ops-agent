package com.jmopsagent.orchestration;

import com.jmopsagent.connector.ConnectorInputValidator;
import com.jmopsagent.domain.IncidentWindow;
import com.jmopsagent.registry.ServiceRegistry;
import org.springframework.stereotype.Component;
import java.time.*;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.regex.Pattern;

/** Resolves concrete hints shared by every entry point; uncertain identity is never invented. */
@Component
public class InvestigationClues {
    private static final Pattern TRACKING = Pattern.compile(
            "(?i)(?<![A-Za-z0-9_-])(?:X-TrackingId|tracking[ -]?id|correlation[ -]?id|request[ -]?id)[\"']?\\s*(?::|=|is)?\\s*[\"']?([A-Za-z0-9][A-Za-z0-9_.:-]{2,255})");
    private static final Pattern UUID = Pattern.compile("(?i)\\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\b");
    private static final Pattern ISO_TIME = Pattern.compile("\\b\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}(?::\\d{2}(?:\\.\\d{1,9})?)?(?:Z|[+-]\\d{2}:\\d{2})?\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern ISO_DATE = Pattern.compile("\\b\\d{4}-\\d{2}-\\d{2}\\b");
    private static final Pattern NAMED_DATE = Pattern.compile("(?i)\\b(Jan(?:uary)?|Feb(?:ruary)?|Mar(?:ch)?|Apr(?:il)?|May|Jun(?:e)?|Jul(?:y)?|Aug(?:ust)?|Sep(?:tember)?|Oct(?:ober)?|Nov(?:ember)?|Dec(?:ember)?)\\s+(\\d{1,2})(?:st|nd|rd|th)?(?:,?\\s+(20\\d{2}))?\\b");
    private static final List<String> MONTHS = List.of("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec");
    private static final Pattern SLASH_DATE = Pattern.compile("(?<![A-Za-z0-9_./-])(\\d{1,2})/(\\d{1,2})(?:/(20\\d{2}|\\d{2}))?(?![A-Za-z0-9_./-])");
    private static final String MONTH_NAME = "(?:Jan(?:uary)?|Feb(?:ruary)?|Mar(?:ch)?|Apr(?:il)?|May|Jun(?:e)?|Jul(?:y)?|Aug(?:ust)?|Sep(?:tember)?|Oct(?:ober)?|Nov(?:ember)?|Dec(?:ember)?)";
    private static final Pattern MONTH_PERIOD = Pattern.compile("(?i)\\b(?:(?:in|during)\\s+(" + MONTH_NAME + ")(?:\\s+(20\\d{2}))?|(" + MONTH_NAME + ")\\s+(20\\d{2}))\\b(?!\\s+\\d{1,2}(?:st|nd|rd|th)?\\b)");
    private static final Pattern RELATIVE_PERIOD = Pattern.compile("(?i)\\b(?:yesterday|today|last (?:week|month|year)|(?:[0-9]{1,4}|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve) (?:days?|weeks?|months?|years?) ago)\\b");
    private static final Pattern DATE_TOKEN = Pattern.compile(ISO_TIME.pattern() + "|" + ISO_DATE.pattern() + "|"
            + NAMED_DATE.pattern() + "|" + SLASH_DATE.pattern() + "|" + MONTH_PERIOD.pattern() + "|" + RELATIVE_PERIOD.pattern(), Pattern.CASE_INSENSITIVE);
    private final ServiceRegistry registry;
    private final InvestigationInputProperties input;

    public InvestigationClues(ServiceRegistry registry, InvestigationInputProperties input) {
        this.registry = registry;
        this.input = input;
    }

    public boolean latestTrackingRequested(String text) {
        if (text == null) return false;
        return Pattern.compile("(?i)\\b(?:last|latest|newest|most recent)\\s+"
                + "(?:(?:observed|used|application|business|successful|failed)\\s+){0,2}"
                + "(?:tracking[ -]?id|request[ -]?id|correlation[ -]?id)\\b").matcher(text).find();
    }

    public boolean diagnosisRequested(String text) {
        return text != null && Pattern.compile("(?i)\\b(why|broken|failing|failed|errors?|cause|not working|doesn't work|does not work)\\b").matcher(text).find();
    }

    public boolean targetChangeRequested(String text) {
        return text != null && Pattern.compile("(?i)^\\s*(?:please |can you )?(?:(?:switch|move) to |change the (?:service|environment|target) to |investigate |focus on |look at )").matcher(text).find();
    }

    public boolean mentionsQa(String text) { return text != null && Pattern.compile("(?i)\\bQA\\b").matcher(text).find(); }


    public Resolved resolve(String problem, String serviceHint, String trackingHint, String environmentHint, Instant now) {
        String text = problem == null ? "" : problem.trim();
        String tracking = optional(trackingHint);
        if (tracking == null) tracking = trackingId(text).orElse(null);
        String service = optional(serviceHint);
        if (service != null) {
            service = ConnectorInputValidator.service(service);
            service = registry.resolve(service).map(com.jmopsagent.registry.ServiceDefinition::service).orElse(service);
        }
        if (service == null) service = service(text).orElse(null);
        if (tracking == null && service == null && looksLikeStandaloneTrackingId(text)) {
            tracking = ConnectorInputValidator.trackingId(text);
        }
        if (service == null && tracking == null) {
            throw new IllegalArgumentException("Include the affected service or a tracking ID in your description");
        }
        String environment = optional(environmentHint);
        var environmentMatcher = Pattern.compile("(?i)\\b(DEV|TEST|PROD|PRODUCTION)\\b").matcher(text);
        Set<String> mentionedEnvironments = new LinkedHashSet<>();
        while (environmentMatcher.find()) mentionedEnvironments.add(environmentMatcher.group(1).toUpperCase(Locale.ROOT));
        if (environment != null && mentionedEnvironments.size() == 1
                && !mentionedEnvironments.contains(environment.toUpperCase(Locale.ROOT))) {
            throw new IllegalArgumentException("The environment hint conflicts with the description; clarify the affected environment");
        }
        if (environment == null && mentionedEnvironments.isEmpty() && mentionsQa(text)) environment = "TEST";
        if (environment == null) {
            if (mentionedEnvironments.size() != 1) throw new IllegalArgumentException("Select the environment or include DEV or TEST in your description");
            environment = mentionedEnvironments.iterator().next();
        }
        return new Resolved(service, tracking == null ? null : ConnectorInputValidator.trackingId(tracking), environment,
                window(text, now).orElse(null));
    }

    public Optional<String> trackingId(String text) {
        if (text == null) return Optional.empty();
        Set<String> values = new LinkedHashSet<>();
        var matcher = TRACKING.matcher(text);
        while (matcher.find()) {
            String value = matcher.group(1).replaceAll("[.,:]+$", "");
            if (Set.of("is", "the", "not", "unknown", "unavailable", "missing", "used", "for", "from", "on", "of", "in", "that", "with", "please", "which", "was", "last", "latest", "yesterday", "today").contains(value.toLowerCase(Locale.ROOT))) continue;
            if (latestTrackingRequested(text) && value.matches("[A-Za-z]+")) continue;
            values.add(ConnectorInputValidator.trackingId(value));
        }
        if (!values.isEmpty()) return values.size() == 1 ? Optional.of(values.iterator().next()) : Optional.empty();
        var uuid = UUID.matcher(text);
        while (uuid.find()) values.add(uuid.group());
        return values.size() == 1 ? Optional.of(values.iterator().next()) : Optional.empty();
    }

    public Optional<String> service(String text) {
        LinkedHashSet<String> matches = new LinkedHashSet<>();
        String subject = text.split("\\s+", 2)[0].replaceAll("[,:]$", "");
        var direct = registry.resolve(subject);
        if (direct.isPresent()) return Optional.of(direct.get().service());
        for (var definition : registry.all()) {
            List<String> names = new ArrayList<>(definition.aliases());
            names.add(definition.service());
            if (names.stream().anyMatch(name -> Pattern.compile("(?i)(?<![A-Za-z0-9_.-])" + Pattern.quote(name)
                    + "(?![A-Za-z0-9_.-])").matcher(text).find())) matches.add(definition.service());
        }
        if (matches.size() > 1) throw new IllegalArgumentException("Several services were mentioned; add the affected service as a hint");
        return matches.stream().findFirst();
    }

    public Optional<String> environment(String text) {
        var values = Pattern.compile("(?i)\\b(DEV|TEST|PROD|PRODUCTION)\\b").matcher(text).results()
                .map(match -> match.group().toUpperCase(Locale.ROOT)).distinct().toList();
        if (values.size() > 1) throw new IllegalArgumentException("Several environments were mentioned; clarify the affected environment");
        return values.stream().findFirst();
    }

    public boolean currentRequested(String text) {
        return text != null && Pattern.compile("(?i)\\b(right now|currently|current (?:requests?|calls?|traffic|errors?|status)|(?:failing|broken|not working|happening) now)\\b").matcher(text).find();
    }

    public Optional<IncidentWindow> window(String text, Instant now) {
        if (text == null || text.isBlank()) return Optional.empty();
        List<IncidentWindow> periods = new ArrayList<>();
        List<Instant> times = new ArrayList<>();
        var tokens = DATE_TOKEN.matcher(incidentDateText(text));
        try {
            while (tokens.find()) {
                String value = tokens.group();
                if (SLASH_DATE.matcher(value).matches()) {
                    String prefix = text.substring(Math.max(0, tokens.start() - 16), tokens.start()).toLowerCase(Locale.ROOT);
                    if (prefix.matches(".*\\b(version|ratio|fraction|score|http|https)\\s*[:=]?\\s*$")) continue;
                }
                if (ISO_TIME.matcher(value).matches()) {
                    Instant time;
                    try { time = OffsetDateTime.parse(value).toInstant(); }
                    catch (DateTimeParseException ignored) { time = LocalDateTime.parse(value).toInstant(ZoneOffset.UTC); }
                    if (time.isAfter(now)) throw new IllegalArgumentException("The incident timestamp is in the future; clarify the time");
                    times.add(time);
                } else periods.add(calendarPeriod(value, now));
            }
        } catch (DateTimeException invalid) {
            throw new IllegalArgumentException("The incident date or timestamp is invalid for " + input.getDateOrder()
                    + "; clarify it using a full date such as September 5, 2026");
        }
        if (times.size() + periods.size() > 2 || !times.isEmpty() && !periods.isEmpty())
            throw new IllegalArgumentException("Several incident times were mentioned; clarify the affected period");
        if (times.size() == 1) return Optional.of(around(times.getFirst(), now));
        if (times.size() == 2) return boundedWindow(times.getFirst(), times.getLast());
        if (periods.size() == 1) return Optional.of(periods.getFirst());
        if (periods.size() == 2) {
            if (periods.getFirst().start().isAfter(periods.getLast().start()))
                throw new IllegalArgumentException("The incident dates are reversed; clarify the affected period");
            return boundedWindow(periods.getFirst().start(), periods.getLast().end());
        }
        return Optional.empty();
    }

    private IncidentWindow calendarPeriod(String value, Instant now) {
        LocalDate today = now.atOffset(ZoneOffset.UTC).toLocalDate();
        if (ISO_DATE.matcher(value).matches()) return day(LocalDate.parse(value), now).orElseThrow();
        var named = NAMED_DATE.matcher(value);
        if (named.matches()) {
            int month = monthNumber(named.group(1));
            int year = named.group(3) == null ? today.getYear() : Integer.parseInt(named.group(3));
            LocalDate date = LocalDate.of(year, month, Integer.parseInt(named.group(2)));
            if (named.group(3) == null && date.isAfter(today)) date = date.minusYears(1);
            return day(date, now).orElseThrow();
        }
        var numeric = SLASH_DATE.matcher(value);
        if (numeric.matches()) {
            int first = Integer.parseInt(numeric.group(1)), second = Integer.parseInt(numeric.group(2));
            int month = input.getDateOrder() == InvestigationInputProperties.DateOrder.MONTH_DAY ? first : second;
            int day = input.getDateOrder() == InvestigationInputProperties.DateOrder.MONTH_DAY ? second : first;
            int year = numeric.group(3) == null ? today.getYear() : Integer.parseInt(numeric.group(3));
            if (year < 100) year += 2000;
            LocalDate date = LocalDate.of(year, month, day);
            if (numeric.group(3) == null && date.isAfter(today)) date = date.minusYears(1);
            return day(date, now).orElseThrow();
        }
        var month = MONTH_PERIOD.matcher(value);
        if (month.matches()) {
            String name = month.group(1) == null ? month.group(3) : month.group(1);
            String year = month.group(1) == null ? month.group(4) : month.group(2);
            LocalDate start = LocalDate.of(year == null ? today.getYear() : Integer.parseInt(year), monthNumber(name), 1);
            if (year == null && start.isAfter(today)) start = start.minusYears(1);
            return calendarRange(start, start.plusMonths(1), now);
        }
        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.equals("yesterday")) return day(today.minusDays(1), now).orElseThrow();
        if (lower.equals("today")) return day(today, now).orElseThrow();
        String[] parts = lower.split(" ");
        int count = parts[0].equals("last") ? 1 : number(parts[0]);
        if (parts[1].startsWith("day")) return day(today.minusDays(count), now).orElseThrow();
        if (parts[1].startsWith("week")) {
            LocalDate start = today.with(java.time.temporal.TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusWeeks(count);
            return calendarRange(start, start.plusWeeks(1), now);
        }
        if (parts[1].startsWith("month")) {
            LocalDate start = today.withDayOfMonth(1).minusMonths(count);
            return calendarRange(start, start.plusMonths(1), now);
        }
        LocalDate start = today.withDayOfYear(1).minusYears(count);
        return calendarRange(start, start.plusYears(1), now);
    }

    private static int monthNumber(String value) { return MONTHS.indexOf(value.substring(0, 3).toLowerCase(Locale.ROOT)) + 1; }
    private static int number(String value) {
        List<String> words = List.of("zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve");
        return words.contains(value) ? words.indexOf(value) : Integer.parseInt(value);
    }
    private IncidentWindow calendarRange(LocalDate start, LocalDate end, Instant now) {
        return boundedWindow(start.atStartOfDay().toInstant(ZoneOffset.UTC), min(end.atStartOfDay().toInstant(ZoneOffset.UTC), now)).orElseThrow();
    }

    private static boolean looksLikeStandaloneTrackingId(String text) {
        return UUID.matcher(text).matches() || text.matches("(?i)(?:DEMO[-_]TRACE|TRACE|TRACK|CORR|REQUEST)[-_:][A-Za-z0-9_.:-]{3,200}");
    }

    private static String incidentDateText(String text) {
        StringBuilder result = new StringBuilder(text);
        var matcher = DATE_TOKEN.matcher(text);
        while (matcher.find()) {
            String prefix = text.substring(Math.max(0, matcher.start() - 80), matcher.start()).toLowerCase(Locale.ROOT);
            var signals = Pattern.compile("\\b(errors?|fail\\w*|incident|outage|broken|timeouts?|merged?|commits?|deployed?|restage|fixed?|recovered|resolved|restored)\\b").matcher(prefix);
            String last = "";
            while (signals.find()) last = signals.group();
            if (last.matches("merged?|commits?|deployed?|restage|fixed?|recovered|resolved|restored")) {
                for (int index = matcher.start(); index < matcher.end(); index++) result.setCharAt(index, ' ');
            }
        }
        return result.toString();
    }

    public static IncidentWindow around(Instant time, Instant now) {
        return new IncidentWindow(time.minusSeconds(600), min(time.plusSeconds(600), now));
    }

    private Optional<IncidentWindow> day(LocalDate day, Instant now) {
        Instant start = day.atStartOfDay().toInstant(ZoneOffset.UTC);
        return boundedWindow(start, min(start.plus(Duration.ofDays(1)), now));
    }

    private Optional<IncidentWindow> boundedWindow(Instant start, Instant end) {
        try { return Optional.of(new IncidentWindow(start, end)); }
        catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("The incident period must be in the past, ordered, and span at most one year; clarify the dates"); }
    }

    private static Instant min(Instant left, Instant right) { return left.isBefore(right) ? left : right; }
    private static String optional(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    public record Resolved(String service, String trackingId, String environment, IncidentWindow window) {}
}
