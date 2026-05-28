package com.uicodegen.repository;

import com.uicodegen.model.ApiUsage;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ApiUsageRepository extends MongoRepository<ApiUsage, String> {
    List<ApiUsage> findByUserIdOrderByTimestampDesc(String userId);
    long countByUserId(String userId);
    long countByAction(String action);
}
