package com.vtb.familyhub.task.entity;

public enum TaskStatus {
    CREATED,       // создано родителем
    IN_PROGRESS,   // в процессе выполнения
    SUBMITTED,     // ребенок нажал "Я сделал" (ждет проверки)
    REJECTED,      // Родитель отклонил (отправлено на переделку)
    APPROVED       // родитель подтвердил (деньги начислены)
}