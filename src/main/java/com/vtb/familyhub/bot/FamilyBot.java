package com.vtb.familyhub.bot;

import com.vtb.familyhub.task.entity.Task;
import com.vtb.familyhub.task.entity.TaskStatus;
import com.vtb.familyhub.task.event.TaskSubmittedEvent;
import com.vtb.familyhub.task.service.TaskService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
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
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class FamilyBot extends TelegramLongPollingBot {

    private final String botUsername;
    private final String botToken;
    private final TaskService taskService;

    private enum State { IDLE, WAITING_TITLE, WAITING_REWARD }

    private final Map<Long, String> userRoles = new ConcurrentHashMap<>();
    private final Map<Long, State> userStates = new ConcurrentHashMap<>();
    private final Map<Long, String> draftTaskTitles = new ConcurrentHashMap<>();

    // Храним chatId последнего активного родителя для отправки PUSH-уведомлений
    private volatile Long activeParentChatId = null;

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
    public String getBotUsername() { return botUsername; }

    @Override
    public String getBotToken() { return botToken; }

    /**
     * СЛУШАТЕЛЬ СОБЫТИЙ: Срабатывает, когда ребенок сдает задание (на сайте или в боте)
     */
    @EventListener
    public void onTaskSubmitted(TaskSubmittedEvent event) {
        if (activeParentChatId == null) {
            log.info("Родитель еще не запускал бота, пуш пропущен");
            return;
        }

        Task task = event.getTask();
        String text = "🔔 *Новое выполненное задание!*\n\n" +
                "Ребенок сдал квест: *«" + task.getTitle() + "»*\n" +
                "Награда к выплате: *" + task.getReward() + " ₽*\n\n" +
                "Проверьте работу и подтвердите перевод:";

        List<List<InlineKeyboardButton>> buttons = List.of(List.of(
                btn("💰 Оплатить " + task.getReward() + " ₽", "APPROVE_" + task.getId()),
                btn("❌ Отклонить", "REJECT_" + task.getId())
        ));

        sendSimpleMessageWithKeyboard(activeParentChatId, text, buttons);
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
        String text = update.getMessage().getText().trim();
        State currentState = userStates.getOrDefault(chatId, State.IDLE);

        if ("/start".equals(text) || "меню".equalsIgnoreCase(text)) {
            userStates.put(chatId, State.IDLE);
            userRoles.putIfAbsent(chatId, "PARENT");
            activeParentChatId = chatId; // Запоминаем родителя для пушей
            sendMainMenu(chatId);
            return;
        }

        if (currentState == State.WAITING_TITLE) {
            draftTaskTitles.put(chatId, text);
            userStates.put(chatId, State.WAITING_REWARD);
            sendSimpleMessage(chatId, "💵 Введите *сумму вознаграждения в рублях* (например: `250`):");
        } else if (currentState == State.WAITING_REWARD) {
            try {
                String cleanNumber = text.replaceAll("[^0-9.]", "");
                BigDecimal reward = new BigDecimal(cleanNumber);
                String title = draftTaskTitles.remove(chatId);

                taskService.createTask(title, "Семейный квест", reward, PARENT_ID, CHILD_ID);
                userStates.put(chatId, State.IDLE);

                sendSimpleMessage(chatId, "🎉 Квест *«" + title + "»* на сумму *" + reward +
                        " ₽* успешно назначен!");
                sendMainMenu(chatId);
            } catch (Exception e) {
                sendSimpleMessage(chatId, "⚠️ Введите корректное число (например: `300`):");
            }
        }
    }

    private void handleCallback(Update update) {
        String data = update.getCallbackQuery().getData();
        long chatId = update.getCallbackQuery().getMessage().getChatId();
        int messageId = update.getCallbackQuery().getMessage().getMessageId();
        String currentRole = userRoles.getOrDefault(chatId, "PARENT");

        if ("MENU".equals(data)) {
            userStates.put(chatId, State.IDLE);
            renderMenu(chatId, messageId);
        }
        else if ("SWITCH_ROLE".equals(data)) {
            String newRole = currentRole.equals("PARENT") ? "CHILD" : "PARENT";
            userRoles.put(chatId, newRole);
            if (newRole.equals("PARENT")) activeParentChatId = chatId;
            renderMenu(chatId, messageId);
        }
        else if ("START_CREATE_TASK".equals(data)) {
            userStates.put(chatId, State.WAITING_TITLE);
            sendSimpleMessage(chatId, "✍️ Напишите *название задания* (например: _Помыть посуду_):");
        }
        else if ("LIST_TASKS".equals(data)) {
            renderUnifiedTaskList(chatId, messageId, currentRole);
        }
        else if (data.startsWith("SUBMIT_")) {
            Long taskId = Long.parseLong(data.replace("SUBMIT_", ""));
            try { taskService.submitTask(taskId, CHILD_ID); } catch (Exception ignored) {}
            renderUnifiedTaskList(chatId, messageId, currentRole);
        }
        else if (data.startsWith("CANCEL_")) {
            Long taskId = Long.parseLong(data.replace("CANCEL_", ""));
            try { taskService.cancelSubmission(taskId, CHILD_ID); } catch (Exception ignored) {}
            renderUnifiedTaskList(chatId, messageId, currentRole);
        }
        else if (data.startsWith("REJECT_")) {
            Long taskId = Long.parseLong(data.replace("REJECT_", ""));
            try { taskService.rejectTask(taskId, PARENT_ID); } catch (Exception ignored) {}
            renderUnifiedTaskList(chatId, messageId, currentRole);
        }
        else if (data.startsWith("APPROVE_")) {
            Long taskId = Long.parseLong(data.replace("APPROVE_", ""));
            try {
                taskService.approveTask(taskId, PARENT_ID);
                sendSimpleMessage(chatId, "💸 Награда переведена на счет ребенка!");
            } catch (Exception ignored) {}
            renderUnifiedTaskList(chatId, messageId, currentRole);
        }
        else if (data.startsWith("DELETE_")) {
            Long taskId = Long.parseLong(data.replace("DELETE_", ""));
            try { taskService.deleteTask(taskId, PARENT_ID); } catch (Exception ignored) {}
            renderUnifiedTaskList(chatId, messageId, currentRole);
        }
        else if ("CHECK_BALANCE".equals(data)) {
            BigDecimal balance = taskService.getBalance(CHILD_ID);
            String balanceText = "💳 *Детский счет ВТБ Junior:*\n\n💰 Баланс: *" + balance + " ₽*";
            editMessage(chatId, messageId, balanceText, List.of(List.of(btn("🔙 В меню", "MENU"))));
        }
    }

    private void sendMainMenu(long chatId) {
        String role = userRoles.getOrDefault(chatId, "PARENT");
        SendMessage msg = new SendMessage();
        msg.setChatId(chatId);
        msg.setParseMode("Markdown");
        msg.setText(getMenuText(role));
        msg.setReplyMarkup(new InlineKeyboardMarkup(getMenuButtons(role)));
        try { execute(msg); } catch (TelegramApiException e) { log.error("Ошибка меню", e); }
    }

    private void renderMenu(long chatId, int messageId) {
        String role = userRoles.getOrDefault(chatId, "PARENT");
        editMessage(chatId, messageId, getMenuText(role), getMenuButtons(role));
    }

    private String getMenuText(String role) {
        String roleBadge = role.equals("PARENT") ? "👨‍👩‍👧 Профиль: РОДИТЕЛЬ" : "🧒 Профиль: РЕБЕНОК";
        return "🏛 *ВТБ Семья (Family Hub)*\n\nТекущий режим: *" + roleBadge + "*";
    }

    private List<List<InlineKeyboardButton>> getMenuButtons(String role) {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        if ("PARENT".equals(role)) {
            rows.add(List.of(btn("➕ Назначить квест", "START_CREATE_TASK")));
            rows.add(List.of(btn("📋 Задания семьи", "LIST_TASKS")));
        } else {
            rows.add(List.of(btn("🎯 Мои квесты", "LIST_TASKS")));
            rows.add(List.of(btn("💳 Мой баланс", "CHECK_BALANCE")));
        }
        rows.add(List.of(btn(role.equals("PARENT") ? "🔄 Переключить на Ребенка" : "🔄 Переключить на Родителя", "SWITCH_ROLE")));
        return rows;
    }

    private void renderUnifiedTaskList(long chatId, int messageId, String role) {
        List<Task> tasks = role.equals("PARENT")
                ? taskService.getTasksForParent(PARENT_ID)
                : taskService.getTasksForChild(CHILD_ID);

        StringBuilder sb = new StringBuilder();
        List<List<InlineKeyboardButton>> buttons = new ArrayList<>();

        sb.append(role.equals("PARENT") ? "📋 *Задания семьи:*\n\n" : "🎯 *Твои задания:*\n\n");

        if (tasks.isEmpty()) {
            sb.append("_Список пуст._\n");
        } else {
            int index = 1;
            for (Task t : tasks) {
                String badge = switch (t.getStatus()) {
                    case CREATED, IN_PROGRESS -> "🏃 В работе";
                    case SUBMITTED -> "⏳ Ждет проверки";
                    case REJECTED -> "❌ Отклонено";
                    case APPROVED -> "✅ Оплачено";
                };

                sb.append(index++).append(". *").append(t.getTitle()).append("*\n")
                        .append("   💵 ").append(t.getReward()).append(" ₽ • ").append(badge).append("\n\n");

                if ("CHILD".equals(role)) {
                    if (t.getStatus() == TaskStatus.CREATED ||
                            t.getStatus() == TaskStatus.IN_PROGRESS ||
                            t.getStatus() == TaskStatus.REJECTED) {
                        buttons.add(List.of(btn("✅ Сдать: " + t.getTitle(),
                                "SUBMIT_" + t.getId())));
                    } else if (t.getStatus() == TaskStatus.SUBMITTED) {
                        buttons.add(List.of(btn("↩️ Отозвать: " + t.getTitle(),
                                "CANCEL_" + t.getId())));
                    }
                } else { // PARENT
                    if (t.getStatus() == TaskStatus.SUBMITTED) {
                        buttons.add(List.of(
                                btn("💰 Оплатить", "APPROVE_" + t.getId()),
                                btn("❌ Отклонить", "REJECT_" + t.getId())
                        ));
                    }
                    // Родитель может удалить невыполненную или завершенную таску
                    buttons.add(List.of(btn("🗑 Удалить: " + t.getTitle(), "DELETE_" + t.getId())));
                }
            }
        }

        buttons.add(List.of(btn("🔙 В меню", "MENU")));
        editMessage(chatId, messageId, sb.toString(), buttons);
    }

    private InlineKeyboardButton btn(String text, String callbackData) {
        InlineKeyboardButton button = new InlineKeyboardButton();
        button.setText(text);
        button.setCallbackData(callbackData);
        return button;
    }

    private void editMessage(long chatId, int messageId, String text, List<List<InlineKeyboardButton>> buttons) {
        EditMessageText edit = new EditMessageText();
        edit.setChatId(chatId);
        edit.setMessageId(messageId);
        edit.setParseMode("Markdown");
        edit.setText(text);
        edit.setReplyMarkup(new InlineKeyboardMarkup(buttons));
        try { execute(edit); } catch (TelegramApiException e) {
            sendSimpleMessageWithKeyboard(chatId, text, buttons);
        }
    }

    private void sendSimpleMessage(long chatId, String text) {
        SendMessage msg = new SendMessage();
        msg.setChatId(chatId);
        msg.setParseMode("Markdown");
        msg.setText(text);
        try { execute(msg); } catch (TelegramApiException e) { log.error("Ошибка отправки текста", e); }
    }

    private void sendSimpleMessageWithKeyboard(long chatId, String text, List<List<InlineKeyboardButton>> buttons) {
        SendMessage msg = new SendMessage();
        msg.setChatId(chatId);
        msg.setParseMode("Markdown");
        msg.setText(text);
        msg.setReplyMarkup(new InlineKeyboardMarkup(buttons));
        try { execute(msg); } catch (TelegramApiException e) { log.error("Ошибка отправки", e); }
    }
}