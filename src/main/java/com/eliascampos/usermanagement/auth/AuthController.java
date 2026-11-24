package com.eliascampos.usermanagement.auth;

import com.eliascampos.usermanagement.auth.dto.ForgotPasswordRequest;
import com.eliascampos.usermanagement.auth.dto.LoginRequest;
import com.eliascampos.usermanagement.auth.dto.RefreshRequest;
import com.eliascampos.usermanagement.auth.dto.ResetPasswordRequest;
import com.eliascampos.usermanagement.auth.dto.TokenResponse;
import com.eliascampos.usermanagement.exception.ApiError;
import com.eliascampos.usermanagement.security.AppUserDetails;
import com.eliascampos.usermanagement.security.JwtService;
import com.eliascampos.usermanagement.audit.AuditAction;
import com.eliascampos.usermanagement.audit.AuditService;
import com.eliascampos.usermanagement.user.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.beans.factory.annotation.Value;

import java.util.Locale;
import java.util.Map;

@Tag(name = "Autenticação", description = "Login, emissão de token, refresh e recuperação de senha")
@RestController
@RequestMapping(value = "/auth", produces = "application/json")
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;
    private final UserService userService;
    private final LoginRateLimiter loginRateLimiter;
    private final PasswordResetService passwordResetService;
    private final RefreshRateLimiter refreshRateLimiter;
    private final AuditService auditService;
    private final boolean singleSession;

    public AuthController(AuthenticationManager authenticationManager, JwtService jwtService,
                          UserService userService, LoginRateLimiter loginRateLimiter,
                          PasswordResetService passwordResetService, RefreshRateLimiter refreshRateLimiter,
                          AuditService auditService,
                          @Value("${app.security.single-session:true}") boolean singleSession) {
        this.authenticationManager = authenticationManager;
        this.jwtService = jwtService;
        this.userService = userService;
        this.loginRateLimiter = loginRateLimiter;
        this.passwordResetService = passwordResetService;
        this.refreshRateLimiter = refreshRateLimiter;
        this.auditService = auditService;
        this.singleSession = singleSession;
    }

    @Operation(summary = "Autentica com e-mail e senha",
            description = "Valida as credenciais e devolve um access token (1 h) e um refresh token (7 dias). "
                    + "Bloqueia após 5 tentativas erradas por e-mail + IP em 15 min (429).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Autenticado com sucesso"),
            @ApiResponse(responseCode = "400", description = "Corpo da requisição inválido",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "401", description = "E-mail ou senha inválidos",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "429", description = "Muitas tentativas",
                    headers = @Header(name = "Retry-After",
                            description = "Segundos até poder tentar novamente"),
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = ApiError.class)))
    })
    @PostMapping("/login")
    public TokenResponse login(@Valid @RequestBody LoginRequest request, HttpServletRequest httpRequest) {
        String ip = httpRequest.getRemoteAddr() == null ? "unknown" : httpRequest.getRemoteAddr();
        String emailKey = request.email().trim().toLowerCase(Locale.ROOT);
        String key = emailKey + "@" + ip;
        // Serializa por conta: logins do mesmo usuario em IPs diferentes nao devem gerar 409.
        synchronized (loginRateLimiter.lock(emailKey)) {
            try {
                loginRateLimiter.check(key);
                loginRateLimiter.checkIp(ip);
            } catch (TooManyAttemptsException ex) {
                auditService.recordForActor(AuditAction.RATE_LIMITED, "system", null, emailKey,
                        Map.of("endpoint", "login"));
                throw ex;
            }

            Authentication authentication;
            try {
                authentication = authenticationManager.authenticate(
                        new UsernamePasswordAuthenticationToken(request.email(), request.password()));
            } catch (AuthenticationException ex) {
                loginRateLimiter.recordFailure(key);
                loginRateLimiter.recordFailureIp(ip);
                auditService.recordForActor(AuditAction.LOGIN_FAILED, "system", null, emailKey,
                        Map.of("reason", ex.getClass().getSimpleName()));
                throw ex;
            }
            Object principalObj = authentication.getPrincipal();
            if (!(principalObj instanceof AppUserDetails principal)) {
                loginRateLimiter.recordFailure(key);
                loginRateLimiter.recordFailureIp(ip);
                auditService.recordForActor(AuditAction.LOGIN_FAILED, "system", null,
                        emailKey,
                        Map.of("reason", "PRINCIPAL_INVALID"));
                throw new BadCredentialsException("Principal invalido");
            }
            loginRateLimiter.reset(key);

            // Um novo login derruba refresh tokens emitidos anteriormente, exceto na demo
            // compartilhada, onde isso invalidaria a sessao dos demais visitantes.
            if (singleSession) {
                userService.onSuccessfulLogin(principal.getUser());
            }
            return jwtService.issue(principal.getUser());
        }
    }

    @Operation(summary = "Invalida os refresh tokens do usuário autenticado",
            description = "Encerra a sessão: o refresh token emitido deixa de valer. Exige access token.")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Sessão encerrada"),
            @ApiResponse(responseCode = "401", description = "Token ausente ou inválido",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = ApiError.class)))
    })
    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(Authentication authentication, HttpServletRequest servletRequest) {
        String ip = servletRequest.getRemoteAddr() == null ? "unknown" : servletRequest.getRemoteAddr();
        refreshRateLimiter.check("logout:" + ip);
        refreshRateLimiter.record("logout:" + ip);
        userService.logout(authentication.getName(), singleSession);
    }

    @Operation(summary = "Troca o refresh token por um novo access token",
            description = "Use quando o access token expirar. O refresh é rotativo: cada uso devolve um par novo e invalida o anterior.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Novo par de tokens"),
            @ApiResponse(responseCode = "400", description = "Corpo da requisição inválido",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "401", description = "Refresh token inválido ou expirado",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = ApiError.class)))
    })
    @PostMapping("/refresh")
    public TokenResponse refresh(@Valid @RequestBody RefreshRequest request, HttpServletRequest servletRequest) {
        String key = "refresh:" + (servletRequest.getRemoteAddr() == null ? "unknown" : servletRequest.getRemoteAddr());
        refreshRateLimiter.check(key);
        try {
            TokenResponse response = jwtService.refresh(request.refreshToken(), singleSession);
            refreshRateLimiter.record(key);
            return response;
        } catch (RuntimeException ex) {
            refreshRateLimiter.record(key);
            throw ex;
        }
    }

    @Operation(summary = "Solicita a redefinicao de senha",
            description = "Gera um token de uso unico e entrega pelo canal configurado. "
                    + "Responde 204 mesmo para e-mail inexistente ou inativo, para nao revelar cadastros. "
                    + "Na demo publica retorna 403 READ_ONLY_DEMO.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Solicitacao processada"),
            @ApiResponse(responseCode = "400", description = "Corpo da requisicao invalido",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "403", description = "Demo somente leitura",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "429", description = "Muitas solicitacoes de recuperacao",
                    headers = @Header(name = "Retry-After",
                            description = "Segundos até poder tentar novamente"),
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "503", description = "Canal de entrega indisponivel",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = ApiError.class)))
    })
    @PostMapping("/forgot-password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void forgotPassword(@Valid @RequestBody ForgotPasswordRequest request,
                               HttpServletRequest httpRequest) {
        passwordResetService.requestReset(request.email(), httpRequest.getRemoteAddr());
    }

    @Operation(summary = "Redefine a senha com o token",
            description = "Troca a senha, invalida refresh tokens anteriores e consome o token. "
                    + "Nao devolve tokens de acesso. Na demo publica retorna 403 READ_ONLY_DEMO.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Senha redefinida"),
            @ApiResponse(responseCode = "400", description = "Corpo invalido ou token invalido/expirado",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "403", description = "Demo somente leitura",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = ApiError.class)))
    })
    @PostMapping("/reset-password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        passwordResetService.resetPassword(request.token(), request.newPassword());
    }
}
