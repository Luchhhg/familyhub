package com.vtb.familyhub.task.service;

import com.vtb.familyhub.task.entity.Account;
import com.vtb.familyhub.task.entity.Task;
import com.vtb.familyhub.task.entity.TaskStatus;
import com.vtb.familyhub.task.entity.Transaction;
import com.vtb.familyhub.task.repository.AccountRepository;
import com.vtb.familyhub.task.repository.TaskRepository;
import com.vtb.familyhub.task.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class TaskService {

    private final TaskRepository taskRepository;
    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;

    public Task createTask(String title, String description, BigDecimal reward, Long parentId, Long childId) {
        Task task = Task.builder()
                .title(title)
                .description(description)
                .reward(reward)
                .parentId(parentId)
                .childId(childId)
                .status(TaskStatus.CREATED)
                .build();
        return taskRepository.save(task);
    }

    public List<Task> getTasksForChild(Long childId) {
        return taskRepository.findByChildId(childId);
    }

    public List<Task> getTasksForParent(Long parentId) {
        return taskRepository.findByParentId(parentId);
    }

    @Transactional
    public Task submitTask(Long taskId, Long childId) {
        Task task = taskRepository.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("Задача с id " + taskId + " не найдена"));

        if (!task.getChildId().equals(childId)) {
            throw new IllegalStateException("Это задание привязано к другому ребенку");
        }

        task.setStatus(TaskStatus.SUBMITTED);
        return taskRepository.save(task);
    }

    @Transactional
    public Task approveTask(Long taskId, Long parentId) {
        Task task = taskRepository.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("Задача с id " + taskId + " не найдена"));

        if (!task.getParentId().equals(parentId)) {
            throw new IllegalStateException("Только назначивший родитель может подтвердить выполнение");
        }

        if (task.getStatus() == TaskStatus.APPROVED) {
            throw new IllegalStateException("Задание уже было оплачено ранее");
        }

        task.setStatus(TaskStatus.APPROVED);
        task.setCompletedAt(LocalDateTime.now());

        // Начисляем деньги на баланс ребенка
        Account childAccount = accountRepository.findById(task.getChildId())
                .orElse(new Account(task.getChildId(), BigDecimal.ZERO));
        childAccount.setBalance(childAccount.getBalance().add(task.getReward()));
        accountRepository.save(childAccount);

        transactionRepository.save(Transaction.builder()
                .fromUserId(task.getParentId())
                .toUserId(task.getChildId())
                .amount(task.getReward())
                .description("Награда за квест: " + task.getTitle())
                .paymentType("TASK_REWARD")
                .build());

        return taskRepository.save(task);
    }

    public BigDecimal getBalance(Long userId) {
        return accountRepository.findById(userId)
                .map(Account::getBalance)
                .orElse(BigDecimal.ZERO);
    }

    public List<Transaction> getHistory(Long userId) {
        return transactionRepository.findByToUserIdOrFromUserIdOrderByTimestampDesc(userId, userId);
    }

    @Transactional
    public Task cancelSubmission(Long taskId, Long childId) {
        Task task = taskRepository.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("Задача не найдена"));

        if (!task.getChildId().equals(childId)) {
            throw new IllegalStateException("Задание принадлежит другому ребенку");
        }

        if (task.getStatus() != TaskStatus.SUBMITTED) {
            throw new IllegalStateException("Отозвать можно только задание, находящееся на проверке");
        }

        task.setStatus(TaskStatus.IN_PROGRESS);
        return taskRepository.save(task);
    }

    @Transactional
    public Task rejectTask(Long taskId, Long parentId) {
        Task task = taskRepository.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("Задача не найдена"));

        if (!task.getParentId().equals(parentId)) {
            throw new IllegalStateException("Только родитель может отклонить задание");
        }

        if (task.getStatus() != TaskStatus.SUBMITTED) {
            throw new IllegalStateException("Отклонить можно только сданное задание");
        }

        task.setStatus(TaskStatus.REJECTED);
        return taskRepository.save(task);
    }
}