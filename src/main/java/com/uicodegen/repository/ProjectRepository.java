package com.uicodegen.repository;

import com.uicodegen.model.Project;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProjectRepository extends MongoRepository<Project, String> {

    // Find all projects for a user, ordered by createdAt desc
    @Query(value = "{ 'userId': ?0 }", sort = "{ 'createdAt': -1 }")
    List<Project> findByUserId(String userId);

    // Find a project by our custom 'id' field AND matching userId (ownership check)
    @Query("{ 'id': ?0, 'userId': ?1 }")
    Optional<Project> findByCustomIdAndUserId(String id, String userId);

    // Find by our custom UUID 'id' field
    @Query("{ 'id': ?0 }")
    Optional<Project> findByCustomId(String id);
}
