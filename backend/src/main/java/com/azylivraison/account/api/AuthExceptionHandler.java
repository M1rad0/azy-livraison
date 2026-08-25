package com.azylivraison.account.api;

import com.azylivraison.account.service.InvalidCredentialsException;
import com.azylivraison.account.service.ClientProfileNotFoundException;
import com.azylivraison.account.service.InvalidClientProfileException;
import com.azylivraison.account.service.InvalidRefreshTokenException;
import com.azylivraison.account.service.ClientProfileVersionConflictException;
import com.azylivraison.account.service.UsernameAlreadyExistsException;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(basePackageClasses = AuthController.class)
public class AuthExceptionHandler {

    @ExceptionHandler(InvalidCredentialsException.class)
    public ResponseEntity<AuthErrorResponse> handleInvalidCredentials(InvalidCredentialsException exception) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(new AuthErrorResponse("INVALID_CREDENTIALS", exception.getMessage()));
    }

    @ExceptionHandler(InvalidRefreshTokenException.class)
    public ResponseEntity<AuthErrorResponse> handleInvalidRefreshToken(InvalidRefreshTokenException exception) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(new AuthErrorResponse("INVALID_REFRESH_TOKEN", exception.getMessage()));
    }

    @ExceptionHandler(UsernameAlreadyExistsException.class)
    public ResponseEntity<AuthErrorResponse> handleUsernameAlreadyExists(UsernameAlreadyExistsException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new AuthErrorResponse("USERNAME_ALREADY_EXISTS", exception.getMessage()));
    }

    @ExceptionHandler(ClientProfileNotFoundException.class)
    public ResponseEntity<AuthErrorResponse> handleClientProfileNotFound(ClientProfileNotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new AuthErrorResponse("CLIENT_PROFILE_NOT_FOUND", exception.getMessage()));
    }

    @ExceptionHandler(ClientProfileVersionConflictException.class)
    public ResponseEntity<AuthErrorResponse> handleClientProfileVersionConflict(
            ClientProfileVersionConflictException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new AuthErrorResponse("CLIENT_PROFILE_VERSION_CONFLICT", exception.getMessage()));
    }

    @ExceptionHandler(InvalidClientProfileException.class)
    public ResponseEntity<AuthErrorResponse> handleInvalidClientProfile(InvalidClientProfileException exception) {
        return ResponseEntity.badRequest()
                .body(new AuthErrorResponse("INVALID_CLIENT_PROFILE", exception.getMessage()));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
    public ResponseEntity<AuthErrorResponse> handleInvalidRequest(Exception exception) {
        return ResponseEntity.badRequest()
                .body(new AuthErrorResponse("INVALID_REQUEST", "La requête est invalide."));
    }
}
