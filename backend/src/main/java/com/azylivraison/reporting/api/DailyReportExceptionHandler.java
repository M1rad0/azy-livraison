package com.azylivraison.reporting.api;

import com.azylivraison.dailyworkflow.api.DailyWorkflowErrorResponse;
import com.azylivraison.dailyworkflow.service.DailyWorkflowConflictException;
import com.azylivraison.dailyworkflow.service.DailyWorkflowNotFoundException;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = {AdminDailyReportController.class, ClientDailyReportController.class})
public class DailyReportExceptionHandler {

    @ExceptionHandler(DailyWorkflowNotFoundException.class)
    ResponseEntity<DailyWorkflowErrorResponse> notFound(DailyWorkflowNotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                DailyWorkflowErrorResponse.of("DAILY_REPORT_NOT_FOUND", exception.getMessage()));
    }

    @ExceptionHandler(DailyWorkflowConflictException.class)
    ResponseEntity<DailyWorkflowErrorResponse> conflict(DailyWorkflowConflictException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(
                DailyWorkflowErrorResponse.of("DAILY_REPORT_NOT_AVAILABLE", exception.getMessage()));
    }
}
