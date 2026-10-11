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
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class FamilyBot extends TelegramLongPollingBot {

    private final String botUsername;
    private final String botToken;
    private final TaskService taskService;

    private enum State {
        IDLE,
        WAITING_TITLE,
        WAITING_REWARD
    }

    private final Map<Long, String> userRoles = new ConcurrentHashMap<>();
    private final Map<Long, State> userStates = new ConcurrentHashMap<>();
    private final Map<Long, String> draftTaskTitles = new ConcurrentHashMap<>();

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
        String text = update.getMessage().getText().trim();
        State currentState = userStates.getOrDefault(chatId, State.IDLE);

        if ("/start".equals(text) || "меню".equalsIgnoreCase(text)) {
            userStates.put(chatId, State.IDLE);
            userRoles.putIfAbsent(chatId, "PARENT");
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

                taskService.createTask(title, "Семейное задание", reward, PARENT_ID, CHILD_ID);
                userStates.put(chatId, State.IDLE);

                sendSimpleMessage(chatId, "🎉 Квест *«" + title + "»* на сумму *" + reward + " ₽* успешно назначен!");
                sendMainMenu(chatId);
            } catch (Exception e) {
                sendSimpleMessage(chatId, "⚠️ Введите корректное число (только цифры, например: `300`):");
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
            renderMenu(chatId, messageId);
        }
        else if ("START_CREATE_TASK".equals(data)) {
            userStates.put(chatId, State.WAITING_TITLE);
            sendSimpleMessage(chatId, "✍️ Напишите *название задания* (например: _Выучить стих_, _Убраться в комнате_):");
        }
        else if ("LIST_TASKS".equals(data)) {
            renderUnifiedTaskList(chatId, messageId, currentRole);
        }
        else if (data.startsWith("SUBMIT_")) {
            Long taskId = Long.parseLong(data.replace("SUBMIT_", ""));
            try {
                taskService.submitTask(taskId, CHILD_ID);
            } catch (Exception ignored) {}
            renderUnifiedTaskList(chatId, messageId, currentRole);
        }
        else if (data.startsWith("CANCEL_")) {
            Long taskId = Long.parseLong(data.replace("CANCEL_", ""));
            try {
                taskService.cancelSubmission(taskId, CHILD_ID);
            } catch (Exception ignored) {}
            renderUnifiedTaskList(chatId, messageId, currentRole);
        }
        else if (data.startsWith("REJECT_")) {
            Long taskId = Long.parseLong(data.replace("REJECT_", ""));
            try {
                taskService.rejectTask(taskId, PARENT_ID);
            } catch (Exception ignored) {}
            renderUnifiedTaskList(chatId, messageId, currentRole);
        }
        else if (data.startsWith("APPROVE_")) {
            Long taskId = Long.parseLong(data.replace("APPROVE_", ""));
            try {
                taskService.approveTask(taskId, PARENT_ID);
            } catch (Exception ignored) {}
            renderUnifiedTaskList(chatId, messageId, currentRole);
        }
        else if ("CHECK_BALANCE".equals(data)) {
            BigDecimal balance = taskService.getBalance(CHILD_ID);
            String balanceText = "💳 *Детский счет ВТБ:*\n\n" +
                    "💰 Доступно: *" + balance + " ₽*\n\n" +
                    "_Деньги начисляются сразу после проверки заданий родителем._";

            List<List<InlineKeyboardButton>> rows = new ArrayList<>();
            rows.add(List.of(btn("🔙 Назад в меню", "MENU")));
            editMessage(chatId, messageId, balanceText, rows);
        }
    }

    private void sendMainMenu(long chatId) {
        String role = userRoles.getOrDefault(chatId, "PARENT");
        String text = getMenuText(role);
        List<List<InlineKeyboardButton>> keyboard = getMenuButtons(role);

        SendMessage msg = new SendMessage();
        msg.setChatId(chatId);
        msg.setParseMode("Markdown");
        msg.setText(text);
        msg.setReplyMarkup(new InlineKeyboardMarkup(keyboard));

        try {
            execute(msg);
        } catch (TelegramApiException e) {
            log.error("Ошибка отправки меню", e);
        }
    }

    private void renderMenu(long chatId, int messageId) {
        String role = userRoles.getOrDefault(chatId, "PARENT");
        editMessage(chatId, messageId, getMenuText(role), getMenuButtons(role));
    }

    private String getMenuText(String role) {
        String roleBadge = role.equals("PARENT") ? "👨‍👩‍👧 Режим: РОДИТЕЛЬ" : "🧒 Режим: РЕБЕНОК";
        return "🏛 *ВТБ Семья (Family Hub)*\n" +
                "Управление совместными финансами и мотивацией детей.\n\n" +
                "Текущий профиль: *" + roleBadge + "*";
    }

    private List<List<InlineKeyboardButton>> getMenuButtons(String role) {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();

        // Кнопка открытия Mini-App интерфейса
        InlineKeyboardButton webAppBtn = new InlineKeyboardButton();
        webAppBtn.setText("📱 Открыть ВТБ Family App");

        // Для локального теста можно указать URL вашего туннеля ngrok/Cloudflare
        // org.telegram.telegrambots.meta.api.objects.webapp.WebAppInfo
        org.telegram.telegrambots.meta.api.objects.webapp.WebAppInfo webAppInfo =
                new org.telegram.telegrambots.meta.api.objects.webapp.WebAppInfo("http://localhost:8080");
        webAppBtn.setWebApp(webAppInfo);

        rows.add(List.of(webAppBtn));

        if ("PARENT".equals(role)) {
            rows.add(List.of(btn("➕ Назначить квест", "START_CREATE_TASK")));
            rows.add(List.of(btn("📋 Список заданий семьи", "LIST_TASKS")));
        } else {
            rows.add(List.of(btn("🎯 Мои квесты и награды", "LIST_TASKS")));
            rows.add(List.of(btn("💳 Мой баланс в ВТБ", "CHECK_BALANCE")));
        }

        String switchText = role.equals("PARENT") ? "🔄 Переключить на Ребенка" : "🔄 Переключить на Родителя";
        rows.add(List.of(btn(switchText, "SWITCH_ROLE")));

        return rows;
    }

    private void renderUnifiedTaskList(long chatId, int messageId, String role) {
        List<Task> tasks = role.equals("PARENT")
                ? taskService.getTasksForParent(PARENT_ID)
                : taskService.getTasksForChild(CHILD_ID);

        StringBuilder sb = new StringBuilder();
        List<List<InlineKeyboardButton>> buttons = new ArrayList<>();

        if ("PARENT".equals(role)) {
            sb.append("📋 *Список заданий семьи:*\n\n");
        } else {
            sb.append("🎯 *Твои задания и цели:*\n\n");
        }

        if (tasks.isEmpty()) {
            sb.append("_Активных заданий пока нет. Создайте первое задание!_\n");
        } else {
            int index = 1;
            for (Task t : tasks) {
                String statusBadge;
                switch (t.getStatus()) {
                    case CREATED -> statusBadge = "🏃 В процессе";
                    case IN_PROGRESS -> statusBadge = "🏃 В работе";
                    case SUBMITTED -> statusBadge = "⏳ Ждет проверки родителя";
                    case REJECTED -> statusBadge = "❌ Отклонено (нужно переделать)";
                    case APPROVED -> statusBadge = "✅ Оплачено (+ " + t.getReward() + " ₽)";
                    default -> statusBadge = t.getStatus().name();
                }

                sb.append(index++).append(". *").append(t.getTitle()).append("*\n")
                        .append("   💵 Награда: *").append(t.getReward()).append(" ₽*\n")
                        .append("   Статус: ").append(statusBadge).append("\n\n");

                // Кнопки действий
                if ("CHILD".equals(role)) {
                    if (t.getStatus() == TaskStatus.CREATED || t.getStatus() == TaskStatus.IN_PROGRESS || t.getStatus() == TaskStatus.REJECTED) {
                        buttons.add(List.of(btn("✅ Сдать: " + t.getTitle(), "SUBMIT_" + t.getId())));
                    } else if (t.getStatus() == TaskStatus.SUBMITTED) {
                        // Откат для ребенка, если отправил по ошибке
                        buttons.add(List.of(btn("↩️ Отозвать: " + t.getTitle(), "CANCEL_" + t.getId())));
                    }
                } else if ("PARENT".equals(role) && t.getStatus() == TaskStatus.SUBMITTED) {
                    // Родитель может либо одобрить, либо отклонить спам/халтуру
                    buttons.add(List.of(
                            btn("💰 Оплатить (" + t.getReward() + " ₽)", "APPROVE_" + t.getId()),
                            btn("❌ Отклонить", "REJECT_" + t.getId())
                    ));
                }
            }
        }

        buttons.add(List.of(btn("🔙 Назад в меню", "MENU")));
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

        try {
            execute(edit);
        } catch (TelegramApiException e) {
            sendSimpleMessageWithKeyboard(chatId, text, buttons);
        }
    }

    private void sendSimpleMessage(long chatId, String text) {
        SendMessage msg = new SendMessage();
        msg.setChatId(chatId);
        msg.setParseMode("Markdown");
        msg.setText(text);
        try {
            execute(msg);
        } catch (TelegramApiException e) {
            log.error("Ошибка отправки текста", e);
        }
    }

    private void sendSimpleMessageWithKeyboard(long chatId, String text, List<List<InlineKeyboardButton>> buttons) {
        SendMessage msg = new SendMessage();
        msg.setChatId(chatId);
        msg.setParseMode("Markdown");
        msg.setText(text);
        msg.setReplyMarkup(new InlineKeyboardMarkup(buttons));
        try {
            execute(msg);
        } catch (TelegramApiException e) {
            log.error("Ошибка отправки", e);
        }
    }
}