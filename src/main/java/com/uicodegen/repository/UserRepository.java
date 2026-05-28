package com.uicodegen.repository;

import com.uicodegen.model.User;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UserRepository extends MongoRepository<User, String> {

    // Find by our custom 'id' UUID field (not MongoDB's _id)
    @Query("{ 'id': ?0 }")
    Optional<User> findByCustomId(String id);

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    @Query("{ 'id': ?0, 'isDeleted': false }")
    Optional<User> findActiveById(String id);

    @Query("{ 'email': ?0, 'isDeleted': false }")
    Optional<User> findActiveByEmail(String email);
}
