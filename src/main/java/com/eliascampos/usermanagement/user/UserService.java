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
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
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
        // Limita a ordenacao a campos indexados e seguros, evitando sort por
        // campos sensiveis (ex.: passwordHash) ou full scan em campos sem indice.
        java.util.Set<String> allowedSortFields = java.util.Set.of(
                "name", "email", "role", "active", "createdAt", "updatedAt"
        );
        java.util.List<Sort.Order> safeOrders = pageable.getSort().stream()
                .filter(order -> allowedSortFields.contains(order.getProperty()))
                .toList();
        Sort safeSort = safeOrders.isEmpty() ? Sort.unsorted() : Sort.by(safeOrders);
        Pageable safePageable = PageRequest.of(
                pageable.getPageNumber(), pageable.getPageSize(), safeSort
        );

        List<Criteria> filters = new ArrayList<>();

        if (StringUtils.hasText(search)) {
            String regex = "^" + Pattern.quote(search.trim());
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
        List<User> users = mongoTemplate.find(new Query(criteria).with(safePageable), User.class);

        Page<UserResponse> page = new PageImpl<>(
                users.stream().map(UserResponse::from).toList(),
                pageable,
                total
        );
        return PageResponse.from(page);
    }

    public synchronized UserResponse update(String id, UserUpdateRequest request) {
        User user = findEntityById(id);
        String email = normalizeEmail(request.email());

        if (userRepository.existsByEmailAndIdNot(email, id)) {
            throw new DuplicateEmailException(email);
        }

        String oldName = user.getName();
        String oldEmail = user.getEmail();
        UserRole oldRole = user.getRole();

        if (user.getRole() == UserRole.ADMIN && request.role() != UserRole.ADMIN
                && user.isActive() && countActiveAdmins() <= 1) {
            throw new com.eliascampos.usermanagement.exception.BusinessRuleException(
                    "Nao e possivel rebaixar o ultimo administrador ativo");
        }

        user.update(request.name().trim(), email, request.role());
        if (!oldEmail.equals(email)) {
            // O token foi enviado para o e-mail antigo: nao deve continuar valendo.
            user.clearPasswordReset();
        }
        if (oldRole != request.role()) {
            // Mudanca de papel invalida refresh tokens antigos.
            user.rotateTokenVersion();
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

    public synchronized UserResponse changeStatus(String id, UserStatusRequest request) {
        User user = findEntityById(id);

        // Evita auto-desativacao e lockout do ultimo ADMIN.
        if (!request.active()) {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.getName() != null && auth.getName().equals(user.getId())) {
                throw new com.eliascampos.usermanagement.exception.BusinessRuleException("Nao e possivel desativar o proprio usuario");
            }
            if (user.getRole() == UserRole.ADMIN && countActiveAdmins() <= 1) {
                throw new com.eliascampos.usermanagement.exception.BusinessRuleException("Nao e possivel desativar o ultimo administrador ativo");
            }
        }

        boolean oldActive = user.isActive();
        user.changeStatus(request.active());
        if (!request.active()) {
            user.rotateTokenVersion();
        }
        User saved = userRepository.save(user);
        auditService.record(AuditAction.USER_STATUS_CHANGED, saved, Map.of(
                "active", Map.of("from", oldActive, "to", saved.isActive())
        ));
        return UserResponse.from(saved);
    }

    public synchronized void delete(String id) {
        User user = findEntityById(id);

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getName() != null && auth.getName().equals(user.getId())) {
            throw new com.eliascampos.usermanagement.exception.BusinessRuleException("Nao e possivel excluir o proprio usuario");
        }
        if (user.getRole() == UserRole.ADMIN && user.isActive() && countActiveAdmins() <= 1) {
            throw new com.eliascampos.usermanagement.exception.BusinessRuleException("Nao e possivel excluir o ultimo administrador ativo");
        }

        userRepository.delete(user);
        auditService.record(AuditAction.USER_DELETED, user, Map.of(
                "name", user.getName(),
                "email", user.getEmail(),
                "role", user.getRole().name()
        ));
    }

    public void onSuccessfulLogin(User user) {
        user.rotateTokenVersion();
        userRepository.save(user);
    }

    public void changePassword(String userId, ChangePasswordRequest request) {
        User user = userRepository.findById(userId)
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

    public void logout(String userId, boolean rotateVersion) {
        userRepository.findById(userId).ifPresent(user -> {
            if (!rotateVersion) {
                return;
            }
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

    private long countActiveAdmins() {
        org.springframework.data.mongodb.core.query.Criteria criteria =
                org.springframework.data.mongodb.core.query.Criteria.where("role").is(UserRole.ADMIN.name())
                        .and("active").is(true);
        return mongoTemplate.count(new org.springframework.data.mongodb.core.query.Query(criteria), User.class);
    }

    private String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
