package com.skypro.FirstTeamPetShelter.service.bot.Impl;

import com.pengrad.telegrambot.TelegramBot;
import com.pengrad.telegrambot.model.*;
import com.pengrad.telegrambot.request.GetFile;
import com.pengrad.telegrambot.request.SendMessage;
import com.pengrad.telegrambot.request.SendPhoto;
import com.skypro.FirstTeamPetShelter.model.*;
import com.skypro.FirstTeamPetShelter.service.*;
import com.skypro.FirstTeamPetShelter.service.bot.BotHandler;
import com.skypro.FirstTeamPetShelter.service.bot.BotService;
import com.skypro.FirstTeamPetShelter.enums.Menu;
import com.skypro.FirstTeamPetShelter.service.bot.helper.BotHelper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Service
@Transactional
public class BotHandlerImpl implements BotHandler {
    Shelter shelter = null;
    long petId = 0;
    Report report = null;

    @Autowired
    private BotService botService;

    @Autowired
    private InfoService infoService;

    @Autowired
    private UserService userService;

    @Autowired
    private AdopterService adopterService;

    @Autowired
    private ReportService reportService;

    @Autowired
    private ShelterService shelterService;

    @Autowired
    private ShelterImageService shelterImageService;

    @Autowired
    private PetService petService;

    @Autowired
    private BotHelper botHelper;

    @Override
    public void updateHandle(TelegramBot telegramBot, Update update) throws IOException {
        if (update.message().text() != null) {
            if (update.message().text().equals("/start")) {
                // проверка пользователя - кто это - новый, юзер, усыновитель или волонтер
                switch (botService.getVisitorRole(update.message().from().id())) {
                    case NEWBIE -> {
                        sendUpdateMessage("Start", telegramBot, update, Menu.START);
                    }
                    case USER -> {
                        userUpdateHandle(telegramBot, update);
                    }
                    case ADOPTER -> {
                        adopterUpdateHandle(telegramBot, update);
                    }
                    case VOLUNTEER -> {
                        volunteerUpdateHandle(telegramBot, update);
                    }
                }
            }
            if (update.message().text().equals("/info")) {
                sendUpdateMessage("BotInformation", telegramBot, update, null);
            }
            if (update.message().text().equals("/call_volunteer")) {
                sendUpdateMessage("CallingVolunteer", telegramBot, update, null);
                botService.callVolunteer(telegramBot, update);
            }
            if (update.message().text().contains("+7-9")) {
                botService.setUserPhone(update.message().text(), telegramBot, update);
            }
            if (update.message().text().toLowerCase().contains("диета") && report != null) {
                report.setPetDiet(update.message().text().toLowerCase().replace("диета", ""));
                sendUpdateMessage("ReportPetHealthAndAdaptation", telegramBot, update, null);
            }
            if (update.message().text().toLowerCase().contains("здоровье") && report != null) {
                report.setPetHealthAndAdaptation(update.message().text().toLowerCase().replace("здоровье", ""));
                sendUpdateMessage("ReportPetChangeBehavior", telegramBot, update, null);
            }
            if (update.message().text().toLowerCase().contains("поведение") && report != null) {
                report.setChangeBehavior(update.message().text().toLowerCase().replace("поведение", ""));
                reportService.addReport(report);
                report = null;
                sendUpdateMessage("ReportCompleteAndSave", telegramBot, update, null);
            }
        }
        if (update.message().document() != null && report != null) {
            File file = telegramBot.execute(new GetFile(update.message().document().fileId())).file();
            report.setPetPhoto(telegramBot.getFileContent(file));
            sendUpdateMessage("ReportPetDiet", telegramBot, update, null);
        }
    }

    @Override
    public void callbackHandle(TelegramBot telegramBot, CallbackQuery callbackQuery) {
        // если да, то в бд пишем да и выдаем Готовы выбрать приют и животное? если нет, то Свяжемся позже и может вы готовы выбрать и список приютов
        if (callbackQuery.data().equals("NotContacted")) {
            sendCallbackMessage("StartNotContacted", telegramBot, callbackQuery, Menu.START, null);
        } else if (callbackQuery.data().equals("Contacted")) {
            userService.editUser(userService.getUserByTelegramId(callbackQuery.from().id())).setContacted(true);
            sendCallbackMessage("StartContacted", telegramBot, callbackQuery, Menu.START, null);
        } else if (callbackQuery.data().contains("Shelter_")) {
            long shelterId = Long.parseLong(callbackQuery.data().replace("Shelter_", ""));
            shelter = shelterService.getShelter(shelterId);
            byte[] shelterImage = shelterImageService.getShelterImageByShelterId(shelterId).getShelterAvatar();
            sendCallbackImageMessage("ShelterHello", telegramBot, callbackQuery, Menu.SHELTER_BASE, shelter, shelterImage);
        }
        if (callbackQuery.data().equals("ReportFromAdopter")) {
            report = botService.reportFromAdopterStart(telegramBot, callbackQuery.from().id());
        }
        if (callbackQuery.data().contains("Report_")) {
            long reportId = Long.parseLong(callbackQuery.data().replace("Report_", ""));
            Report r = reportService.getReportById(reportId);
            String m = "Дата отчета: " + r.getReportDate()
                    + "Диета: " + r.getPetDiet()
                    + "Здоровье и адоптация: " + r.getPetHealthAndAdaptation()
                    + "Изменения в поведении: " + r.getChangeBehavior();
            if (r.getPetPhoto() != null) {
                botService.reportImage(m, telegramBot, callbackQuery.from().id(), r.getPetPhoto(), r);
            } else {
                botService.reportMessage(telegramBot, callbackQuery.from().id(), m, r);
            }
        }
        if (callbackQuery.data().contains("RYes_")) {
            long reportId = Long.parseLong(callbackQuery.data().replace("RYes_", ""));
            reportService.getReportById(reportId).setReviewed(true);
            botService.executeMessage(telegramBot, callbackQuery.from().id(), "Отчет " + reportService.getReportById(reportId).getAdopter().getAdopterName() + " проверен!", null);
            botService.executeMessage(telegramBot, reportService.getReportById(reportId).getAdopter().getAdopterTelegramId(), "Ваш отчёт за " + reportService.getReportById(reportId).getReportDate() + " проверен!", null);
        }
        if (callbackQuery.data().contains("UserBecomeAdoptive_")) {
            long userBecomeAdoptiveId = Long.parseLong(callbackQuery.data().replace("UserBecomeAdoptive_", ""));
            botService.adoptive(telegramBot, callbackQuery.from().id(), userBecomeAdoptiveId);
        }
        if (callbackQuery.data().contains("AdoptiveYes_")) {
            long userId = Long.parseLong(callbackQuery.data().replace("AdoptiveYes_", ""));
            UserApp userApp = userService.getUser(userId);
            long tlgId = userApp.getUserTelegramId();
            Adopter adopter = new Adopter();
            adopter.setAdopterPhoneNumber(userApp.getUserPhoneNumber());
            adopter.setAdopterTelegramId(tlgId);
            adopter.setAdopterName(userApp.getUserName());
            adopterService.addAdopter(adopter);
            Pet pet = petService.getPetByPotentialOwner(userId);
            pet.setPetOwner(adopterService.getAdopterByTelegramId(tlgId));
            userApp.setBecomeAdoptive(false);
            pet.setPetPotentialOwner(null);
            userService.deleteUser(userId);
            pet.setPetPotentialOwner(null);
            botService.executeMessage(telegramBot, tlgId, "Вас утвердили в усыновители! Ждём ежедневного отчёта.", null);
            botService.executeMessage(telegramBot, callbackQuery.from().id(), "Усыновитель сохранён в БД!", null);
        }
        if (shelter != null) {
            String shelterType = firstLetterToUpperCase(shelter.getShelterType());
            if (callbackQuery.data().equals("ShelterInfo")) {
                byte[] shelterImage = shelterImageService.getShelterImageByShelterId(shelter.getId()).getShelterDrivingDirection();
                sendCallbackImageMessage(shelter.getShelterInfo(), telegramBot, callbackQuery, null, shelter, shelterImage);
            }
            if (callbackQuery.data().equals("ShelterSecurity")) {
                botService.executeMessage(telegramBot, callbackQuery.from().id(), shelter.getSecurityContactDetail(), null);
            }
            if (callbackQuery.data().equals("ShelterSafety")) {
                botService.executeMessage(telegramBot, callbackQuery.from().id(), shelter.getSafetyInformation(), null);
            }
            if (callbackQuery.data().equals("ShelterFindPet")) {
                botService.getPets(telegramBot, callbackQuery, shelter);
            }
            if (callbackQuery.data().equals("PhoneNumber")) {
                sendCallbackMessage("PhoneNumber", telegramBot, callbackQuery, null, null);
            }
            if (callbackQuery.data().contains("PetSelect_")) {
                petId = Long.parseLong(callbackQuery.data().replace("PetSelect_", ""));
                Menu menu = null;
                if (shelter.getShelterType().equalsIgnoreCase("dog")) {
                    menu = Menu.SHELTER_DOGS;
                } else if (shelter.getShelterType().equalsIgnoreCase("cat")) {
                    menu = Menu.SHELTER_CATS;
                }
                botService.executeImageMessage(petService.getPet(petId).getPetName(), telegramBot, callbackQuery, menu, petService.getPet(petId).getPetAvatar().getSmallAvatar(), shelter);
            }
            if (callbackQuery.data().equals("PetRules")) {
                sendCallbackMessage(shelterType + "PetRules", telegramBot, callbackQuery, null, null);
            }
            if (callbackQuery.data().equals("PetNeedDocuments")) {
                sendCallbackMessage(shelterType + "PetNeedDocuments", telegramBot, callbackQuery, null, null);
            }
            if (callbackQuery.data().equals("PetTransport")) {
                sendCallbackMessage(shelterType + "PetTransport", telegramBot, callbackQuery, null, null);
            }
            if (callbackQuery.data().equals("PetHome")) {
                sendCallbackMessage(shelterType + "PetHome", telegramBot, callbackQuery, null, null);
            }
            if (callbackQuery.data().equals("DogHandlerAdvice")) {
                sendCallbackMessage("DogHandlerAdvice", telegramBot, callbackQuery, null, null);
            }
            if (callbackQuery.data().equals("DogHandlers")) {
                sendCallbackMessage("DogHandlers", telegramBot, callbackQuery, null, null);
            }
            if (callbackQuery.data().equals("PetReasonsForRefusal")) {
                sendCallbackMessage(shelterType + "PetReasonsForRefusal", telegramBot, callbackQuery, null, null);
            }
            if (callbackQuery.data().equals("GetPet")) {
                if (petId != 0) {
                    botService.setAdoptiveParent(petService.getPet(petId), telegramBot, callbackQuery);
                }
            }
        }
    }

    private String firstLetterToUpperCase(String word) {
        word = word.toLowerCase();
        return word.substring(0, 1).toUpperCase() + word.substring(1);
    }

    private void volunteerUpdateHandle(TelegramBot telegramBot, Update update) {
        // Приветствие волонтера
        sendUpdateMessage("HelloVolunteer", telegramBot, update, null);

        // Вывод списка пользователей, зовущих волонтера, если таковые есть
        if (botHelper.getUsersCallingVolunteer() != null) {
            sendUpdateMessage("CallingUsers", telegramBot, update, Menu.CALLING_USERS);
        } else {
            sendUpdateMessage("NotCallingUsers", telegramBot, update, null);
        }

        // Вывод списка усыновителей, зовущих волонтера, если таковые есть
        if (botHelper.getAdoptersCallingVolunteer() != null) {
            sendUpdateMessage("CallingAdopters", telegramBot, update, Menu.CALLING_ADOPTERS);
        } else {
            sendUpdateMessage("NotCallingAdopters", telegramBot, update, null);
        }

        // Вывод списка усыновителей, чьи отчёты нужно проверить сегодня, если таковые есть
        if (botHelper.getAdoptersReportCheck() != null) {
            sendUpdateMessage("AdoptersReportCheck", telegramBot, update, Menu.CHECK_REPORTS);
        } else {
            sendUpdateMessage("NotAdoptersReportCheck", telegramBot, update, null);
        }

        // Вывод списка пользователей, желающих стать усыновителями, если таковые есть
        if (botHelper.getUsersBecomeAdoptive() != null) {
            sendUpdateMessage("UsersBecomeAdoptive", telegramBot, update, Menu.USERS_BECOME_ADOPTIVE);
        } else {
            sendUpdateMessage("NotUsersBecomeAdoptive", telegramBot, update, null);
        }
    }

    private void adopterUpdateHandle(TelegramBot telegramBot, Update update) {
        // если усыновитель, то проверка когда был отчет, сколько времени. если нужен отчет, то просим, если рано то рано говорим, и выдаем в обоих случаях меню усыновителя
        // если усыновитель и пришла дата окончания проверки и нет продления, а также нарушений, то поздравление иначе отказ и возврат животного
        sendUpdateMessage("HelloAdopter", telegramBot, update, Menu.ADOPTER_SEND_REPORT);
        String messageText;
        long adopterId = adopterService.getAdopterByTelegramId(update.message().from().id()).getId();
        // Если список отчетов усыновителя не пуст
        if (reportService.getReportsByAdopter(adopterId) != null) {
            if (reportService.getReportsByAdopterAndReviewed(adopterId, false) != null) {
                String localDateTimes = reportService.getReportsByAdopterAndReviewed(adopterId, false)
                        .stream()
                        .map(Report::getReportDate)
                        .toList()
                        .toString();

                messageText = infoService.getMessage("ReportIsReviewedFalse");
                messageText += " Непроверенные отчеты за даты: " + localDateTimes;
                botService.sendResponseFromUpdate(telegramBot, update, messageText, null);
            } else {
                if (reportService.getReportsByAdopterAndReviewed(adopterId, true) != null) {
                    LocalDateTime reportDateTime = reportService.getReportsByAdopterAndReviewed(adopterId, true)
                            .stream()
                            .map(Report::getReportDate)
                            .max(LocalDateTime::compareTo)
                            .orElse(null);
                    if (reportDateTime != null
                            && reportDateTime.getDayOfMonth() == LocalDate.now().getDayOfMonth()
                            && reportDateTime.isBefore(LocalDate.now().atTime(21, 1))) {
                        messageText = infoService.getMessage("ReportsIsReviewedTrue");
                        botService.sendResponseFromUpdate(telegramBot, update, messageText, null);
                    }
                } else {
                    messageText = infoService.getMessage("ReportNotSend");
                    botService.sendResponseFromUpdate(telegramBot, update, messageText, Menu.ADOPTER_SEND_REPORT);
                }
            }
        } else {
            messageText = infoService.getMessage("ReportNotSend");
            botService.sendResponseFromUpdate(telegramBot, update, messageText, Menu.ADOPTER_SEND_REPORT);
        }
    }

    private void userUpdateHandle(TelegramBot telegramBot, Update update) {
        // если юзер, то приветствие и проверка в бд связывались ли с ним. если в бд не связывались, то вопрос к юзеру связывались?
        if (userService.getUserByTelegramId(update.message().from().id()).isContacted()) {
            sendUpdateMessage("Start", telegramBot, update, Menu.START);
        } else {
            sendUpdateMessage("StartNotContacted", telegramBot, update, Menu.ANSWER_CONTACTED);
        }
    }

    private void sendUpdateMessage(String key_word, TelegramBot telegramBot, Update update, Menu menu) {
        String messageText = infoService.getMessage(key_word);
        botService.sendResponseFromUpdate(telegramBot, update, messageText, menu);
    }

    private void sendCallbackMessage(String key_word, TelegramBot telegramBot, CallbackQuery callbackQuery, Menu menu, Shelter shelter) {
        String messageText = infoService.getMessage(key_word);
        botService.sendResponseFromCallback(telegramBot, callbackQuery, messageText, menu, shelter);
    }

    private void sendCallbackImageMessage(String message, TelegramBot telegramBot, CallbackQuery callbackQuery, Menu menu, Shelter shelter, byte[] shelterImage) {
        String caption = message.equals("ShelterHello") ? infoService.getMessage(message) : message;
        botService.executeImageMessage(caption, telegramBot, callbackQuery, menu, shelterImage, shelter);
    }
}
