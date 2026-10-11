package com.vtb.familyhub.task.repository;

import com.vtb.familyhub.task.entity.Task;
import com.vtb.familyhub.task.entity.TaskStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TaskRepository extends JpaRepository<Task, Long> {
    List<Task> findByChildId(Long childId);
    List<Task> findByParentId(Long parentId);
    List<Task> findByChildIdAndStatus(Long childId, TaskStatus status);
}