package com.jmopsagent.controller;

import jakarta.persistence.EntityNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;

@ControllerAdvice
public class SafeControllerAdvice {

    @ExceptionHandler(EntityNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public String notFound(Model model) {
        model.addAttribute("status", 404);
        model.addAttribute("error", "Not Found");
        model.addAttribute("message", "The requested investigation was not found.");
        return "error";
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public String badRequest(IllegalArgumentException ex, Model model) {
        model.addAttribute("status", 400);
        model.addAttribute("error", "Bad Request");
        model.addAttribute("message", ex.getMessage());
        return "error";
    }
}
