package com.vtb.familyhub.task.controller;

import com.vtb.familyhub.task.entity.Task;
import com.vtb.familyhub.task.service.TaskService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;

@RestController
@RequestMapping("/api/v1/tasks")
@RequiredArgsConstructor
@Tag(name = "Задания (Tasks)", description = "API для управления заданиями и вознаграждениями")
public class TaskController {

    private final TaskService taskService;

    @PostMapping
    @Operation(summary = "Создать новое задание для ребенка")
    public ResponseEntity<Task> create(@RequestBody CreateTaskRequest req) {
        return ResponseEntity.ok(taskService.createTask(
                req.getTitle(), req.getDescription(), req.getReward(), req.getParentId(), req.getChildId()
        ));
    }

    @GetMapping("/child/{childId}")
    @Operation(summary = "Получить список заданий ребенка")
    public ResponseEntity<List<Task>> getByChild(@PathVariable Long childId) {
        return ResponseEntity.ok(taskService.getTasksForChild(childId));
    }

    @GetMapping("/parent/{parentId}")
    @Operation(summary = "Получить список заданий родителя")
    public ResponseEntity<List<Task>> getByParent(@PathVariable Long parentId) {
        return ResponseEntity.ok(taskService.getTasksForParent(parentId));
    }

    @PatchMapping("/{id}/submit")
    @Operation(summary = "Ребенок отправляет задание на проверку родителя")
    public ResponseEntity<Task> submit(@PathVariable Long id, @RequestParam Long childId) {
        return ResponseEntity.ok(taskService.submitTask(id, childId));
    }

    @PatchMapping("/{id}/approve")
    @Operation(summary = "Родитель подтверждает выполнение и начисляет награду")
    public ResponseEntity<Task> approve(@PathVariable Long id, @RequestParam Long parentId) {
        return ResponseEntity.ok(taskService.approveTask(id, parentId));
    }

    @PatchMapping("/{id}/cancel-submit")
    @Operation(summary = "Ребенок отзывает ошибочно отправленное задание")
    public ResponseEntity<Task> cancelSubmit(@PathVariable Long id, @RequestParam Long childId) {
        return ResponseEntity.ok(taskService.cancelSubmission(id, childId));
    }

    @PatchMapping("/{id}/reject")
    @Operation(summary = "Родитель отклоняет задание и возвращает на доработку")
    public ResponseEntity<Task> reject(@PathVariable Long id, @RequestParam Long parentId) {
        return ResponseEntity.ok(taskService.rejectTask(id, parentId));
    }

    @Data
    public static class CreateTaskRequest {
        private String title;
        private String description;
        private BigDecimal reward;
        private Long parentId;
        private Long childId;
    }

    @GetMapping("/balance/{userId}")
    @Operation(summary = "Получить текущий баланс пользователя (ребенка)")
    public ResponseEntity<BigDecimal> getBalance(@PathVariable Long userId) {
        return ResponseEntity.ok(taskService.getBalance(userId));
    }
}