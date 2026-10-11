package com.vtb.familyhub.task.event;

import com.vtb.familyhub.task.entity.Task;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public class TaskSubmittedEvent {
    private final Task task;
}