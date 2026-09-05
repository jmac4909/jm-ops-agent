(function () {
    "use strict";

    var TERMINAL_STATUSES = ["COMPLETED", "FAILED"];
    var ACTIVE_STATUSES = ["CREATED", "DISCOVERING", "COLLECTING_EVIDENCE", "ANALYZING", "NEEDS_MORE_EVIDENCE", "CODE_INVESTIGATION"];

    function normalize(value) {
        return String(value || "").trim().toUpperCase().replace(/[\s-]+/g, "_");
    }

    function humanize(value) {
        return String(value || "")
            .replace(/[_-]+/g, " ")
            .replace(/\b\w/g, function (letter) { return letter.toUpperCase(); });
    }

    function initializeSidebar() {
        var toggle = document.querySelector(".sidebar-toggle");
        var scrim = document.querySelector("[data-sidebar-close]");
        if (!toggle || !scrim) {
            return;
        }

        function closeSidebar() {
            document.body.classList.remove("sidebar-open");
            toggle.setAttribute("aria-expanded", "false");
            toggle.setAttribute("aria-label", "Open navigation");
            scrim.hidden = true;
        }

        toggle.addEventListener("click", function () {
            var willOpen = !document.body.classList.contains("sidebar-open");
            document.body.classList.toggle("sidebar-open", willOpen);
            toggle.setAttribute("aria-expanded", String(willOpen));
            toggle.setAttribute("aria-label", willOpen ? "Close navigation" : "Open navigation");
            scrim.hidden = !willOpen;
        });
        scrim.addEventListener("click", closeSidebar);
        document.addEventListener("keydown", function (event) {
            if (event.key === "Escape") {
                closeSidebar();
            }
        });
        window.addEventListener("resize", function () {
            if (window.innerWidth > 920) {
                closeSidebar();
            }
        });
    }

    function initializeTabs() {
        var tabs = Array.prototype.slice.call(document.querySelectorAll("[data-tab-target]"));
        if (!tabs.length) {
            return;
        }

        function activate(tab, updateHash) {
            tabs.forEach(function (candidate) {
                var selected = candidate === tab;
                var panel = document.getElementById(candidate.getAttribute("data-tab-target"));
                candidate.classList.toggle("is-active", selected);
                candidate.setAttribute("aria-selected", String(selected));
                candidate.tabIndex = selected ? 0 : -1;
                if (panel) {
                    panel.hidden = !selected;
                }
            });
            if (updateHash && window.history && window.history.replaceState) {
                var mode = tab.id === "tracking-tab" ? "tracking" : "service";
                window.history.replaceState(null, "", "#" + mode);
            }
        }

        tabs.forEach(function (tab, index) {
            tab.addEventListener("click", function () { activate(tab, true); });
            tab.addEventListener("keydown", function (event) {
                if (["ArrowLeft", "ArrowRight", "Home", "End"].indexOf(event.key) === -1) {
                    return;
                }
                event.preventDefault();
                var nextIndex;
                if (event.key === "Home") {
                    nextIndex = 0;
                } else if (event.key === "End") {
                    nextIndex = tabs.length - 1;
                } else {
                    nextIndex = (index + (event.key === "ArrowRight" ? 1 : -1) + tabs.length) % tabs.length;
                }
                activate(tabs[nextIndex], true);
                tabs[nextIndex].focus();
            });
        });

        var trackingInput = document.getElementById("trackingId");
        var trackingTab = document.getElementById("tracking-tab");
        if (trackingTab && (window.location.hash === "#tracking" || (trackingInput && trackingInput.value.trim()))) {
            activate(trackingTab, false);
        }
    }

    function initializeCharacterCounters() {
        document.querySelectorAll("[data-character-count]").forEach(function (counter) {
            var field = document.getElementById(counter.getAttribute("data-character-count"));
            if (!field) {
                return;
            }
            function update() {
                counter.textContent = field.value.length + " / " + (field.maxLength > 0 ? field.maxLength : "∞");
            }
            field.addEventListener("input", update);
            update();
        });
    }

    function initializeForms() {
        document.querySelectorAll("[data-submit-form]").forEach(function (form) {
            form.addEventListener("submit", function () {
                var button = form.querySelector("button[type='submit']");
                if (!button || button.disabled) {
                    return;
                }
                button.disabled = true;
                button.classList.add("is-loading");
                button.setAttribute("aria-busy", "true");
            });
        });
    }

    function initializeFeedback() {
        var form = document.querySelector("[data-feedback-form]");
        if (!form) {
            return;
        }
        var details = form.querySelector("[data-feedback-details]");
        var rootCause = form.querySelector("[name='actualRootCause']");
        var choices = form.querySelectorAll("input[name='userFeedback']");

        function update() {
            var selected = form.querySelector("input[name='userFeedback']:checked");
            var needsDetails = selected && selected.value !== "YES";
            if (details) {
                details.hidden = !needsDetails;
            }
            if (rootCause) {
                rootCause.required = Boolean(needsDetails);
            }
        }

        choices.forEach(function (choice) { choice.addEventListener("change", update); });
        update();
    }

    function initializeDates() {
        var formatter = new Intl.DateTimeFormat(undefined, {
            month: "short",
            day: "numeric",
            hour: "2-digit",
            minute: "2-digit"
        });
        var fullFormatter = new Intl.DateTimeFormat(undefined, {
            year: "numeric",
            month: "short",
            day: "numeric",
            hour: "2-digit",
            minute: "2-digit",
            second: "2-digit",
            timeZoneName: "short"
        });

        document.querySelectorAll("time[data-format-datetime]").forEach(function (element) {
            var raw = element.getAttribute("datetime");
            if (!raw) {
                return;
            }
            var date = new Date(raw);
            if (Number.isNaN(date.getTime())) {
                return;
            }
            element.textContent = formatter.format(date);
            element.title = fullFormatter.format(date);
        });
    }

    function initializeConfidence() {
        document.querySelectorAll("[data-confidence]").forEach(function (badge) {
            var raw = badge.getAttribute("data-confidence");
            var level = normalize(raw);
            var numeric = Number(raw);
            if (!Number.isNaN(numeric) && raw !== "") {
                level = numeric >= 0.75 ? "HIGH" : numeric >= 0.45 ? "MEDIUM" : "LOW";
            }
            if (["HIGH", "MEDIUM", "LOW"].indexOf(level) === -1) {
                level = "UNKNOWN";
            }
            badge.setAttribute("data-confidence-level", level);
            var label = badge.querySelector("[data-confidence-label]");
            if (label) {
                label.textContent = level;
            }
        });
    }

    function initializeProgress() {
        var rail = document.querySelector("[data-current-status]");
        var page = document.querySelector("[data-investigation-status]");
        if (!rail || !page) {
            return;
        }
        var status = normalize(page.getAttribute("data-investigation-status"));
        var positionByStatus = {
            CREATED: 0,
            DISCOVERING: 0,
            COLLECTING_EVIDENCE: 1,
            NEEDS_MORE_EVIDENCE: 1,
            ANALYZING: 2,
            CODE_INVESTIGATION: 2,
            COMPLETED: 3,
            FAILED: 2
        };
        var current = Object.prototype.hasOwnProperty.call(positionByStatus, status) ? positionByStatus[status] : 0;
        var stages = Array.prototype.slice.call(rail.querySelectorAll("li"));
        stages.forEach(function (stage, index) {
            var complete = status === "COMPLETED" ? index <= current : index < current;
            stage.classList.toggle("is-complete", complete);
            stage.classList.toggle("is-current", status !== "COMPLETED" && index === current);
            stage.classList.toggle("is-failed", status === "FAILED" && index === current);
        });

        var active = ACTIVE_STATUSES.indexOf(status) !== -1;
        document.body.classList.toggle("is-investigating", active);
        var label = document.querySelector("[data-refresh-label]");
        if (label) {
            label.textContent = active ? humanize(status).toLowerCase() : status === "COMPLETED" ? "Investigation complete" : humanize(status);
        }
    }

    function initializeDiagnostics() {
        var cards = Array.prototype.slice.call(document.querySelectorAll("[data-diagnostic-status]"));
        if (!cards.length) {
            return;
        }
        var readyCount = 0;
        cards.forEach(function (card) {
            var raw = normalize(card.getAttribute("data-diagnostic-status"));
            var ready = raw === "TRUE" || /(^|[^A-Z])(AVAILABLE|CONFIGURED|READY|OK)([^A-Z]|$)/.test(raw);
            card.classList.toggle("is-ready", ready);
            readyCount += ready ? 1 : 0;

            var status = card.querySelector(".diagnostic-status");
            if (status) {
                status.textContent = ready ? "Ready" : "Needs setup";
            }
            var detail = card.querySelector("[data-diagnostic-detail]");
            if (detail && ["TRUE", "FALSE", "AVAILABLE", "UNAVAILABLE", "CONFIGURED", "UNCONFIGURED"].indexOf(raw) !== -1) {
                detail.textContent = ready ? "Available for investigations" : "Not currently available";
            }
        });

        document.querySelectorAll("[data-humanize]").forEach(function (element) {
            element.textContent = humanize(element.textContent);
        });
        var summary = document.querySelector("[data-diagnostics-summary]");
        if (summary) {
            summary.textContent = readyCount + " of " + cards.length + " capabilities ready";
        }
        var orb = document.querySelector("[data-diagnostics-orb]");
        if (orb) {
            orb.classList.toggle("has-warning", readyCount !== cards.length);
        }
    }

    function initializeSourceActions() {
        var seen = Object.create(null);
        document.querySelectorAll("[data-source-action]").forEach(function (link) {
            var source = normalize(link.getAttribute("data-source-action"));
            if (seen[source]) {
                link.hidden = true;
            } else {
                seen[source] = true;
            }
        });
    }

    function restoreScrollPosition() {
        var key = "jmops-scroll:" + window.location.pathname;
        try {
            var stored = window.sessionStorage ? window.sessionStorage.getItem(key) : null;
            if (stored !== null) {
                window.sessionStorage.removeItem(key);
                window.scrollTo(0, Number(stored) || 0);
            }
        } catch (ignored) {
            // Session storage can be disabled without affecting the UI.
        }
    }

    function initializeInvestigationRefresh() {
        var page = document.querySelector("[data-investigation-status]");
        if (!page) {
            return;
        }
        var status = normalize(page.getAttribute("data-investigation-status"));
        if (TERMINAL_STATUSES.indexOf(status) !== -1 || ACTIVE_STATUSES.indexOf(status) === -1) {
            return;
        }

        window.setTimeout(function refreshWhenVisible() {
            if (document.visibilityState !== "visible") {
                window.setTimeout(refreshWhenVisible, 4000);
                return;
            }
            try {
                if (window.sessionStorage) {
                    window.sessionStorage.setItem("jmops-scroll:" + window.location.pathname, String(window.scrollY));
                }
            } catch (ignored) {
                // Session storage can be disabled without affecting investigation behavior.
            }
            window.location.reload();
        }, 4000);
    }

    function initialize() {
        initializeSidebar();
        initializeTabs();
        initializeCharacterCounters();
        initializeFeedback();
        initializeDates();
        initializeConfidence();
        initializeProgress();
        initializeDiagnostics();
        initializeSourceActions();
        restoreScrollPosition();
        initializeForms();
        initializeInvestigationRefresh();
    }

    if (document.readyState === "loading") {
        document.addEventListener("DOMContentLoaded", initialize);
    } else {
        initialize();
    }
}());
