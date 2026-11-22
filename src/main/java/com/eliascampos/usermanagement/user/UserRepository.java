package com.eliascampos.usermanagement.user;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface UserRepository extends MongoRepository<User, String> {

    Optional<User> findByEmail(String email);

    Optional<User> findByPasswordResetTokenHash(String passwordResetTokenHash);

    boolean existsByEmail(String email);

    boolean existsByEmailAndIdNot(String email, String id);
}
