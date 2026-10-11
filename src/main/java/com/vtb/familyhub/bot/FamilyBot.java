package com.vtb.familyhub.bot;

import com.vtb.familyhub.task.entity.Task;
import com.vtb.familyhub.task.entity.TaskStatus;
import com.vtb.familyhub.task.service.TaskService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.math.BigDecimal;
import java.util.*;

@Slf4j
@Component
public class FamilyBot extends TelegramLongPollingBot {

    private final String botUsername;
    private final TaskService taskService;
    private final String botToken;

    // В памяти храним текущую выбранную роль пользователя (для удобного демо)
    // chat_id -> "PARENT" или "CHILD"
    private final Map<Long, String> userRoles = new HashMap<>();

    // Демо-идентификаторы
    private static final Long PARENT_ID = 1L;
    private static final Long CHILD_ID = 2L;

    public FamilyBot(@Value("${telegram.bot.token}") String botToken,
                     @Value("${telegram.bot.username}") String botUsername,
                     TaskService taskService) {
        super(botToken);
        this.botToken = botToken;
        this.botUsername = botUsername;
        this.taskService = taskService;
    }

    @Override
    public String getBotUsername() {
        return botUsername;
    }

    @Override
    public String getBotToken() {
        return botToken;
    }

    @Override
    public void onUpdateReceived(Update update) {
        if (update.hasMessage() && update.getMessage().hasText()) {
            handleTextMessage(update);
        } else if (update.hasCallbackQuery()) {
            handleCallback(update);
        }
    }

    private void handleTextMessage(Update update) {
        long chatId = update.getMessage().getChatId();
        String text = update.getMessage().getText();

        if ("/start".equals(text) || "меню".equalsIgnoreCase(text)) {
            userRoles.putIfAbsent(chatId, "PARENT");
            sendMainMenu(chatId);
        }
    }

    private void handleCallback(Update update) {
        String data = update.getCallbackQuery().getData();
        long chatId = update.getCallbackQuery().getMessage().getChatId();
        int messageId = update.getCallbackQuery().getMessage().getMessageId();

        String currentRole = userRoles.getOrDefault(chatId, "PARENT");

        if ("SWITCH_ROLE".equals(data)) {
            String newRole = currentRole.equals("PARENT") ? "CHILD" : "PARENT";
            userRoles.put(chatId, newRole);
            editMenu(chatId, messageId);
        }
        else if ("CREATE_TASK_FAST".equals(data)) {
            // Быстрое создание типового задания для демо
            taskService.createTask("Уборка в комнате", "Навести порядок и пропылесосить", new BigDecimal("250"), PARENT_ID, CHILD_ID);
            sendText(chatId, "🎉 Задание *«Уборка в комнате»* на *250 ₽* создано и отправлено ребенку!");
            sendMainMenu(chatId);
        }
        else if ("LIST_TASKS".equals(data)) {
            showTasks(chatId, currentRole);
        }
        else if (data.startsWith("SUBMIT_")) {
            Long taskId = Long.parseLong(data.replace("SUBMIT_", ""));
            try {
                taskService.submitTask(taskId, CHILD_ID);
                sendText(chatId, "✅ Задание отправлено родителю на проверку!");
            } catch (Exception e) {
                sendText(chatId, "⚠️ Ошибка: " + e.getMessage());
            }
            sendMainMenu(chatId);
        }
        else if (data.startsWith("APPROVE_")) {
            Long taskId = Long.parseLong(data.replace("APPROVE_", ""));
            try {
                taskService.approveTask(taskId, PARENT_ID);
                sendText(chatId, "💸 Задание подтверждено! Деньги успешно начислены на детскую карту ВТБ!");
            } catch (Exception e) {
                sendText(chatId, "⚠️ Ошибка: " + e.getMessage());
            }
            sendMainMenu(chatId);
        }
        else if ("CHECK_BALANCE".equals(data)) {
            BigDecimal balance = taskService.getBalance(CHILD_ID);
            sendText(chatId, "💳 *Детский счет ВТБ:*\nТекущий баланс: *" + balance + " ₽*");
            sendMainMenu(chatId);
        }
    }

    private void sendMainMenu(long chatId) {
        String role = userRoles.getOrDefault(chatId, "PARENT");
        String roleTitle = role.equals("PARENT") ? "👨‍👩‍👧 Режим: РОДИТЕЛЬ" : "🧒 Режим: РЕБЕНОК";

        SendMessage msg = new SendMessage();
        msg.setChatId(chatId);
        msg.setParseMode("Markdown");
        msg.setText("🏛 *ВТБ Семья (Family Hub)*\n" +
                "Единый хаб совместных финансов и заданий.\n\n" +
                "Текущий статус: *" + roleTitle + "*");

        msg.setReplyMarkup(createKeyboard(role));
        try {
            execute(msg);
        } catch (TelegramApiException e) {
            log.error("Ошибка отправки меню", e);
        }
    }

    private void editMenu(long chatId, int messageId) {
        String role = userRoles.getOrDefault(chatId, "PARENT");
        String roleTitle = role.equals("PARENT") ? "👨‍👩‍👧 Режим: РОДИТЕЛЬ" : "🧒 Режим: РЕБЕНОК";

        EditMessageText edit = new EditMessageText();
        edit.setChatId(chatId);
        edit.setMessageId(messageId);
        edit.setParseMode("Markdown");
        edit.setText("🏛 *ВТБ Семья (Family Hub)*\n" +
                "Единый хаб совместных финансов и заданий.\n\n" +
                "Текущий статус: *" + roleTitle + "*");

        edit.setReplyMarkup(createKeyboard(role));
        try {
            execute(edit);
        } catch (TelegramApiException e) {
            log.error("Ошибка редактирования меню", e);
        }
    }

    private InlineKeyboardMarkup createKeyboard(String role) {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();

        if ("PARENT".equals(role)) {
            // Кнопки родителя
            rows.add(List.of(createBtn("➕ Создать квест (+250 ₽)", "CREATE_TASK_FAST")));
            rows.add(List.of(createBtn("📋 Проверить задания семьи", "LIST_TASKS")));
        } else {
            // Кнопки ребенка
            rows.add(List.of(createBtn("🎯 Мои квесты и задания", "LIST_TASKS")));
            rows.add(List.of(createBtn("💰 Мой баланс в ВТБ", "CHECK_BALANCE")));
        }

        // Переключатель роли для удобства показа на демо
        String switchText = role.equals("PARENT") ? "🔄 Переключить на Ребенка" : "🔄 Переключить на Родителя";
        rows.add(List.of(createBtn(switchText, "SWITCH_ROLE")));

        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        markup.setKeyboard(rows);
        return markup;
    }

    private void showTasks(long chatId, String role) {
        List<Task> tasks = role.equals("PARENT")
                ? taskService.getTasksForParent(PARENT_ID)
                : taskService.getTasksForChild(CHILD_ID);

        if (tasks.isEmpty()) {
            sendText(chatId, "Заданий пока нет!");
            return;
        }

        for (Task t : tasks) {
            StringBuilder sb = new StringBuilder();
            sb.append("📌 *").append(t.getTitle()).append("*\n");
            sb.append("💵 Награда: *").append(t.getReward()).append(" ₽*\n");
            sb.append("Статус: `").append(t.getStatus()).append("`\n");

            List<List<InlineKeyboardButton>> buttons = new ArrayList<>();

            if ("CHILD".equals(role) && (t.getStatus() == TaskStatus.CREATED || t.getStatus() == TaskStatus.IN_PROGRESS)) {
                buttons.add(List.of(createBtn("✅ Я выполнил!", "SUBMIT_" + t.getId())));
            } else if ("PARENT".equals(role) && t.getStatus() == TaskStatus.SUBMITTED) {
                buttons.add(List.of(createBtn("💰 Одобрить и перевести " + t.getReward() + " ₽", "APPROVE_" + t.getId())));
            }

            SendMessage msg = new SendMessage();
            msg.setChatId(chatId);
            msg.setParseMode("Markdown");
            msg.setText(sb.toString());

            if (!buttons.isEmpty()) {
                InlineKeyboardMarkup km = new InlineKeyboardMarkup();
                km.setKeyboard(buttons);
                msg.setReplyMarkup(km);
            }

            try {
                execute(msg);
            } catch (TelegramApiException e) {
                log.error("Ошибка отправки карточки задачи", e);
            }
        }
    }

    private InlineKeyboardButton createBtn(String text, String callbackData) {
        InlineKeyboardButton btn = new InlineKeyboardButton();
        btn.setText(text);
        btn.setCallbackData(callbackData);
        return btn;
    }

    private void sendText(long chatId, String text) {
        SendMessage msg = new SendMessage();
        msg.setChatId(chatId);
        msg.setParseMode("Markdown");
        msg.setText(text);
        try {
            execute(msg);
        } catch (TelegramApiException e) {
            log.error("Ошибка отправки сообщения", e);
        }
    }
}