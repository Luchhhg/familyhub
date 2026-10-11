package com.vtb.familyhub;

import com.vtb.familyhub.task.entity.Task;
import com.vtb.familyhub.task.entity.TaskStatus;
import com.vtb.familyhub.task.repository.AccountRepository;
import com.vtb.familyhub.task.repository.TaskRepository;
import com.vtb.familyhub.task.repository.TransactionRepository;
import com.vtb.familyhub.task.service.TaskService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TaskServiceTest {

    @Mock
    private TaskRepository taskRepository;
    @Mock
    private AccountRepository accountRepository;
    @Mock
    private TransactionRepository transactionRepository;
    @Mock
    private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private TaskService taskService;

    @Test
    @DisplayName("Безопасность: нельзя перевести деньги, если квест не был сдан ребенком")
    void shouldThrowExceptionWhenApprovingTaskNotInSubmittedStatus() {
        Task task = Task.builder()
                .id(1L)
                .parentId(1L)
                .childId(2L)
                .reward(new BigDecimal("250"))
                .status(TaskStatus.CREATED) // Задание еще в работе!
                .build();

        when(taskRepository.findById(1L)).thenReturn(Optional.of(task));

        // Ожидаем пресечение несанкционированной выплаты
        assertThrows(IllegalStateException.class, () -> taskService.approveTask(1L, 1L));
    }
}