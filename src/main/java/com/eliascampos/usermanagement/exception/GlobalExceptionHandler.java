package com.eliascampos.usermanagement.exception;

import com.eliascampos.usermanagement.auth.InvalidPasswordResetTokenException;
import com.eliascampos.usermanagement.auth.InvalidRefreshTokenException;
import com.eliascampos.usermanagement.auth.PasswordResetUnavailableException;
import com.eliascampos.usermanagement.auth.TooManyAttemptsException;
import com.eliascampos.usermanagement.auth.TooManyPasswordResetRequestsException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.context.request.WebRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

@RestControllerAdvice
public class GlobalExceptionHandler extends org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ProblemDetail> handleNotFound(ResourceNotFoundException ex, HttpServletRequest request) {
        ProblemDetail problem = problem(HttpStatus.NOT_FOUND, "Recurso nao encontrado", ex.getMessage(), request);
        problem.setProperty("code", "RESOURCE_NOT_FOUND");
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(problem);
    }

    @ExceptionHandler({DuplicateEmailException.class, DuplicateKeyException.class})
    public ResponseEntity<ProblemDetail> handleDuplicate(Exception ex, HttpServletRequest request) {
        String detail = ex instanceof DuplicateEmailException
                ? ex.getMessage()
                : "Ja existe um registro com os mesmos dados unicos";
        ProblemDetail problem = problem(HttpStatus.CONFLICT, "Conflito de dados", detail, request);
        problem.setProperty("code", "EMAIL_ALREADY_EXISTS");
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
                                                                   HttpHeaders headers, HttpStatusCode status,
                                                                   WebRequest request) {
        Map<String, String> errors = new LinkedHashMap<>();
        for (FieldError fieldError : ex.getBindingResult().getFieldErrors()) {
            errors.putIfAbsent(fieldError.getField(), fieldError.getDefaultMessage());
        }

        HttpServletRequest servletRequest = ((org.springframework.web.context.request.ServletWebRequest) request).getRequest();
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "Dados invalidos",
                "Um ou mais campos estao invalidos", servletRequest);
        problem.setProperty("code", "VALIDATION_ERROR");
        problem.setProperty("errors", errors);
        return ResponseEntity.badRequest().body(problem);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex,
                                                                  HttpHeaders headers, HttpStatusCode status,
                                                                  WebRequest request) {
        HttpServletRequest servletRequest = ((org.springframework.web.context.request.ServletWebRequest) request).getRequest();
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "Requisicao invalida",
                "Corpo ou parametro da requisicao invalido", servletRequest);
        problem.setProperty("code", "INVALID_REQUEST");
        return ResponseEntity.badRequest().body(problem);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ProblemDetail> handleTypeMismatch(MethodArgumentTypeMismatchException ex,
                                                           HttpServletRequest request) {
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "Requisicao invalida",
                "Corpo ou parametro da requisicao invalido", request);
        problem.setProperty("code", "INVALID_REQUEST");
        return ResponseEntity.badRequest().body(problem);
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException ex,
                                                                          HttpHeaders headers, HttpStatusCode status,
                                                                          WebRequest request) {
        Map<String, String> errors = new LinkedHashMap<>();
        for (var result : ex.getParameterValidationResults()) {
            for (var error : result.getResolvableErrors()) {
                errors.putIfAbsent(result.getMethodParameter().getParameterName(),
                        error.getDefaultMessage());
            }
        }
        HttpServletRequest servletRequest = ((org.springframework.web.context.request.ServletWebRequest) request).getRequest();
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "Dados invalidos",
                "Um ou mais campos estao invalidos", servletRequest);
        problem.setProperty("code", "VALIDATION_ERROR");
        problem.setProperty("errors", errors);
        return ResponseEntity.badRequest().body(problem);
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ProblemDetail> handleAuthentication(AuthenticationException ex,
                                                             HttpServletRequest request) {
        ProblemDetail problem = problem(HttpStatus.UNAUTHORIZED, "Credenciais invalidas",
                "E-mail ou senha invalidos", request);
        problem.setProperty("code", "INVALID_CREDENTIALS");
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(problem);
    }

    @ExceptionHandler(InvalidRefreshTokenException.class)
    public ResponseEntity<ProblemDetail> handleInvalidRefreshToken(InvalidRefreshTokenException ex,
                                                                  HttpServletRequest request) {
        ProblemDetail problem = problem(HttpStatus.UNAUTHORIZED, "Refresh token invalido",
                "O refresh token e invalido ou expirou", request);
        problem.setProperty("code", "INVALID_REFRESH_TOKEN");
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(problem);
    }

    @ExceptionHandler(BusinessRuleException.class)
    public ResponseEntity<ProblemDetail> handleBusinessRule(BusinessRuleException ex,
                                                           HttpServletRequest request) {
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "Regra de negocio", ex.getMessage(), request);
        problem.setProperty("code", "INVALID_REQUEST");
        return ResponseEntity.badRequest().body(problem);
    }

    @ExceptionHandler(InvalidPasswordException.class)
    public ResponseEntity<ProblemDetail> handleInvalidPassword(InvalidPasswordException ex,
                                                              HttpServletRequest request) {
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "Senha incorreta", ex.getMessage(), request);
        problem.setProperty("code", "INVALID_PASSWORD");
        return ResponseEntity.badRequest().body(problem);
    }

    @ExceptionHandler(TooManyAttemptsException.class)
    public ResponseEntity<ProblemDetail> handleTooManyAttempts(TooManyAttemptsException ex,
                                                               HttpServletRequest request) {
        ProblemDetail problem = problem(HttpStatus.TOO_MANY_REQUESTS, "Muitas tentativas de login",
                "Muitas tentativas de login. Tente novamente mais tarde.", request);
        problem.setProperty("code", "TOO_MANY_ATTEMPTS");
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(ex.getRetryAfterSeconds()))
                .body(problem);
    }

    @ExceptionHandler(InvalidPasswordResetTokenException.class)
    public ResponseEntity<ProblemDetail> handleInvalidPasswordResetToken(InvalidPasswordResetTokenException ex,
                                                                         HttpServletRequest request) {
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "Token invalido",
                "O token de redefinicao e invalido, expirou ou ja foi usado", request);
        problem.setProperty("code", "INVALID_OR_EXPIRED_TOKEN");
        return ResponseEntity.badRequest().body(problem);
    }

    @ExceptionHandler(TooManyPasswordResetRequestsException.class)
    public ResponseEntity<ProblemDetail> handleTooManyPasswordResetRequests(
            TooManyPasswordResetRequestsException ex, HttpServletRequest request) {
        ProblemDetail problem = problem(HttpStatus.TOO_MANY_REQUESTS, "Muitas solicitacoes",
                "Muitas solicitacoes. Tente novamente mais tarde.", request);
        problem.setProperty("code", "TOO_MANY_ATTEMPTS");
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(ex.getRetryAfterSeconds()))
                .body(problem);
    }

    @ExceptionHandler(PasswordResetUnavailableException.class)
    public ResponseEntity<ProblemDetail> handlePasswordResetUnavailable(PasswordResetUnavailableException ex,
                                                                        HttpServletRequest request) {
        ProblemDetail problem = problem(HttpStatus.SERVICE_UNAVAILABLE, "Recuperacao de senha indisponivel",
                "A recuperacao de senha nao esta disponivel neste ambiente", request);
        problem.setProperty("code", "PASSWORD_RESET_UNAVAILABLE");
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(problem);
    }

    @Override
    protected ResponseEntity<Object> handleNoResourceFoundException(NoResourceFoundException ex, HttpHeaders headers,
                                                                   HttpStatusCode status, WebRequest request) {
        HttpServletRequest servletRequest = ((org.springframework.web.context.request.ServletWebRequest) request).getRequest();
        ProblemDetail problem = problem(HttpStatus.NOT_FOUND, "Recurso nao encontrado",
                "Nao existe recurso no caminho solicitado", servletRequest);
        problem.setProperty("code", "RESOURCE_NOT_FOUND");
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(problem);
    }

    @Override
    protected ResponseEntity<Object> handleHttpRequestMethodNotSupported(HttpRequestMethodNotSupportedException ex,
                                                                         HttpHeaders headers, HttpStatusCode status,
                                                                         WebRequest request) {
        HttpServletRequest servletRequest = ((org.springframework.web.context.request.ServletWebRequest) request).getRequest();
        ProblemDetail problem = problem(HttpStatus.METHOD_NOT_ALLOWED, "Metodo nao permitido",
                "O metodo " + ex.getMethod() + " nao e suportado para este recurso", servletRequest);
        problem.setProperty("code", "METHOD_NOT_ALLOWED");
        Set<HttpMethod> supported = ex.getSupportedHttpMethods();
        if (supported != null) {
            headers.setAllow(supported);
        }
        return new ResponseEntity<>(problem, headers, HttpStatus.METHOD_NOT_ALLOWED);
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ProblemDetail> handleOptimisticLock(OptimisticLockingFailureException ex,
                                                             HttpServletRequest request) {
        ProblemDetail problem = problem(HttpStatus.CONFLICT, "Conflito de concorrencia",
                "O recurso foi alterado por outra requisicao. Tente novamente.", request);
        problem.setProperty("code", "CONFLICT");
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("Erro inesperado ao processar {}", request.getRequestURI(), ex);
        ProblemDetail problem = problem(HttpStatus.INTERNAL_SERVER_ERROR, "Erro interno",
                "Ocorreu um erro inesperado. Tente novamente mais tarde.", request);
        problem.setProperty("code", "INTERNAL_ERROR");
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(problem);
    }

    private ProblemDetail problem(HttpStatus status, String title, String detail, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("timestamp", java.time.Instant.now());
        return problem;
    }
}
