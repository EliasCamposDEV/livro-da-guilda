package com.eliascampos.usermanagement.user;

import com.eliascampos.usermanagement.audit.AuditAction;
import com.eliascampos.usermanagement.audit.AuditService;
import com.eliascampos.usermanagement.exception.DuplicateEmailException;
import com.eliascampos.usermanagement.exception.InvalidPasswordException;
import com.eliascampos.usermanagement.exception.ResourceNotFoundException;
import com.eliascampos.usermanagement.user.dto.ChangePasswordRequest;
import com.eliascampos.usermanagement.user.dto.PageResponse;
import com.eliascampos.usermanagement.user.dto.UserRequest;
import com.eliascampos.usermanagement.user.dto.UserResponse;
import com.eliascampos.usermanagement.user.dto.UserStatusRequest;
import com.eliascampos.usermanagement.user.dto.UserUpdateRequest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.regex.Pattern;

@Service
public class UserService {

    private final UserRepository userRepository;
    private final MongoTemplate mongoTemplate;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;

    public UserService(UserRepository userRepository, MongoTemplate mongoTemplate,
                       PasswordEncoder passwordEncoder, AuditService auditService) {
        this.userRepository = userRepository;
        this.mongoTemplate = mongoTemplate;
        this.passwordEncoder = passwordEncoder;
        this.auditService = auditService;
    }

    public UserResponse create(UserRequest request) {
        String email = normalizeEmail(request.email());
        if (userRepository.existsByEmail(email)) {
            throw new DuplicateEmailException(email);
        }

        User user = new User(request.name().trim(), email, request.role(),
                passwordEncoder.encode(request.password()));
        try {
            User saved = userRepository.save(user);
            auditService.record(AuditAction.USER_CREATED, saved, Map.of(
                    "name", saved.getName(),
                    "email", saved.getEmail(),
                    "role", saved.getRole().name()
            ));
            return UserResponse.from(saved);
        } catch (DuplicateKeyException ex) {
            throw new DuplicateEmailException(email);
        }
    }

    public UserResponse findById(String id) {
        return UserResponse.from(findEntityById(id));
    }

    public PageResponse<UserResponse> findAll(String search, UserRole role, Boolean active, Pageable pageable) {
        List<Criteria> filters = new ArrayList<>();

        if (StringUtils.hasText(search)) {
            String regex = Pattern.quote(search.trim());
            filters.add(new Criteria().orOperator(
                    Criteria.where("name").regex(regex, "i"),
                    Criteria.where("email").regex(regex, "i")
            ));
        }
        if (role != null) {
            filters.add(Criteria.where("role").is(role.name()));
        }
        if (active != null) {
            filters.add(Criteria.where("active").is(active));
        }

        Criteria criteria = filters.isEmpty()
                ? new Criteria()
                : new Criteria().andOperator(filters.toArray(Criteria[]::new));

        long total = mongoTemplate.count(new Query(criteria), User.class);
        List<User> users = mongoTemplate.find(new Query(criteria).with(pageable), User.class);

        Page<UserResponse> page = new PageImpl<>(
                users.stream().map(UserResponse::from).toList(),
                pageable,
                total
        );
        return PageResponse.from(page);
    }

    public UserResponse update(String id, UserUpdateRequest request) {
        User user = findEntityById(id);
        String email = normalizeEmail(request.email());

        if (userRepository.existsByEmailAndIdNot(email, id)) {
            throw new DuplicateEmailException(email);
        }

        String oldName = user.getName();
        String oldEmail = user.getEmail();
        UserRole oldRole = user.getRole();

        user.update(request.name().trim(), email, request.role());
        if (!oldEmail.equals(email)) {
            // O token foi enviado para o e-mail antigo: nao deve continuar valendo.
            user.clearPasswordReset();
        }
        try {
            User saved = userRepository.save(user);
            auditService.record(AuditAction.USER_UPDATED, saved, Map.of(
                    "name", Map.of("from", oldName, "to", saved.getName()),
                    "email", Map.of("from", oldEmail, "to", saved.getEmail()),
                    "role", Map.of("from", oldRole.name(), "to", saved.getRole().name())
            ));
            return UserResponse.from(saved);
        } catch (DuplicateKeyException ex) {
            throw new DuplicateEmailException(email);
        }
    }

    public UserResponse changeStatus(String id, UserStatusRequest request) {
        User user = findEntityById(id);
        boolean oldActive = user.isActive();
        user.changeStatus(request.active());
        User saved = userRepository.save(user);
        auditService.record(AuditAction.USER_STATUS_CHANGED, saved, Map.of(
                "active", Map.of("from", oldActive, "to", saved.isActive())
        ));
        return UserResponse.from(saved);
    }

    public void delete(String id) {
        User user = findEntityById(id);
        auditService.record(AuditAction.USER_DELETED, user, Map.of(
                "name", user.getName(),
                "email", user.getEmail(),
                "role", user.getRole().name()
        ));
        userRepository.delete(user);
    }

    public void changePassword(String email, ChangePasswordRequest request) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("Usuario autenticado nao encontrado"));

        if (!passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
            throw new InvalidPasswordException();
        }

        user.changePassword(passwordEncoder.encode(request.newPassword()));
        // Qualquer refresh token anterior deixa de valer: a senha mudou.
        user.rotateTokenVersion();
        User saved = userRepository.save(user);
        auditService.record(AuditAction.PASSWORD_CHANGED, saved, Map.of(
                "email", saved.getEmail()
        ));
    }

    public void logout(String email) {
        userRepository.findByEmail(email).ifPresent(user -> {
            user.rotateTokenVersion();
            User saved = userRepository.save(user);
            auditService.record(AuditAction.USER_LOGGED_OUT, saved, Map.of(
                    "email", saved.getEmail()
            ));
        });
    }

    private User findEntityById(String id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Usuario nao encontrado para o id " + id));
    }

    private String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
