package com.vtb.familyhub.task.repository;

import com.vtb.familyhub.task.entity.Transaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface TransactionRepository extends JpaRepository<Transaction, Long> {
    List<Transaction> findByToUserIdOrFromUserIdOrderByTimestampDesc(Long toUserId, Long fromUserId);
}