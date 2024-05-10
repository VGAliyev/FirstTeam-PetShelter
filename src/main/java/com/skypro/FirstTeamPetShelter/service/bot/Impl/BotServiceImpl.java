package com.skypro.FirstTeamPetShelter.service.bot.Impl;

import com.pengrad.telegrambot.TelegramBot;
import com.pengrad.telegrambot.model.CallbackQuery;
import com.pengrad.telegrambot.model.Update;
import com.pengrad.telegrambot.model.User;
import com.pengrad.telegrambot.model.request.InlineKeyboardButton;
import com.pengrad.telegrambot.model.request.InlineKeyboardMarkup;
import com.pengrad.telegrambot.request.SendMessage;
import com.pengrad.telegrambot.request.SendPhoto;
import com.skypro.FirstTeamPetShelter.model.*;
import com.skypro.FirstTeamPetShelter.service.*;
import com.skypro.FirstTeamPetShelter.service.bot.BotMenuService;
import com.skypro.FirstTeamPetShelter.service.bot.BotService;
import com.skypro.FirstTeamPetShelter.enums.Menu;
import com.skypro.FirstTeamPetShelter.enums.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class BotServiceImpl implements BotService {
    private final Logger logger = LoggerFactory.getLogger(BotService.class);

    private final UserService userService;
    private final AdopterService adopterService;
    private final VolunteerService volunteerService;
    private final BotMenuService botMenuService;
    private final InfoService infoService;
    private final PetService petService;
    private final PetAvatarService petAvatarService;

    public BotServiceImpl(UserService userService, AdopterService adopterService, VolunteerService volunteerService, BotMenuService botMenuService, InfoService infoService, PetService petService, PetAvatarService petAvatarService) {
        this.userService = userService;
        this.adopterService = adopterService;
        this.volunteerService = volunteerService;
        this.botMenuService = botMenuService;
        this.infoService = infoService;
        this.petService = petService;
        this.petAvatarService = petAvatarService;
    }

    @Override
    public Role getVisitorRole(Long visitorId) {
        if (userService.getUserByTelegramId(visitorId) != null) {
            return Role.USER;
        } else if (adopterService.getAdopterByTelegramId(visitorId) != null) {
            return Role.ADOPTER;
        } else if (volunteerService.getVolunteerByTelegramId(visitorId) != null) {
            return Role.VOLUNTEER;
        } else {
            return Role.NEWBIE;
        }
    }

    @Override
    public void sendResponseFromUpdate(TelegramBot telegramBot, Update update, String messageText, Menu menu) {
        User telegramUser = update.message().from();
        messageText = parseMessageText(telegramUser, null, messageText);
        executeMessage(telegramBot, update.message().chat().id(), messageText, menu);
    }

    @Override
    public void sendResponseFromCallback(TelegramBot telegramBot, CallbackQuery callbackQuery, String messageText, Menu menu, Shelter shelter) {
        User telegramUser = callbackQuery.from();
        messageText = parseMessageText(telegramUser, shelter, messageText);
        executeMessage(telegramBot, telegramUser.id(), messageText, menu);
    }

    @Override
    public void callVolunteer(TelegramBot telegramBot, Update update) {
        Role role = getVisitorRole(update.message().from().id());
        if (role == Role.NEWBIE) {
            UserApp userApp = new UserApp();
            String messageText = infoService.getMessage("PhoneNumber");
            sendResponseFromUpdate(telegramBot, update, messageText, Menu.USERNAME_PHONE_NUMBER);
            userApp.setUserName(update.message().from().firstName());
            userApp.setUserTelegramId(update.message().from().id());
            userApp.setContacted(false);
            userService.addUser(userApp);
        } else if (role == Role.USER) {
            userService.getUserByTelegramId(update.message().from().id()).setContacted(false);
        } else if (role == Role.ADOPTER) {
            adopterService.getAdopterByTelegramId(update.message().from().id()).setContacted(false);
        } else {
            return;
        }

        sendMessageToRandomVolunteer(telegramBot, update);
    }

    @Override
    public void getPets(TelegramBot telegramBot, CallbackQuery callbackQuery, Shelter shelter) {
        // todo: Реализовать пагинацию
        List<Pet> pets = petService.getAllPets().stream().toList();
        for (Pet pet: pets) {
            SendPhoto sendPhoto = new SendPhoto(callbackQuery.from().id(), petAvatarService.getPetAvatarByPet(pet.getId()).getSmallAvatar());
            sendPhoto.caption(pet.getPetName() + " " + pet.getPetAge() + " " + pet.getPetGender());
            sendPhoto.replyMarkup(new InlineKeyboardMarkup(new InlineKeyboardButton("Выбрать").callbackData("PetSelect_" + pet.getId())));
            telegramBot.execute(sendPhoto);
        }
    }

    @Override
    public void setUserPhone(String phone, TelegramBot telegramBot, Update update) {
        Pattern pattern = Pattern.compile("^\\+7\\-9\\d{2}-\\d{3}-\\d{2}-\\d{2}$");
        Matcher matcher = pattern.matcher(phone);
        if (matcher.matches()) {
            Role role = getVisitorRole(update.message().from().id());
            switch (role) {
                case NEWBIE -> {
                    UserApp userApp = new UserApp();
                    userApp.setContacted(false);
                    userApp.setUserTelegramId(update.message().from().id());
                    userApp.setUserName(update.message().from().firstName());
                    userApp.setUserPhoneNumber(phone);
                    userService.addUser(userApp);
                }
                case USER -> {
                    userService.getUserByTelegramId(update.message().from().id()).setUserPhoneNumber(phone);
                }
                case ADOPTER -> {
                    adopterService.getAdopterByTelegramId(update.message().from().id()).setAdopterPhoneNumber(phone);
                }
                case VOLUNTEER -> {}
            }
            executeMessage(telegramBot, update.message().from().id(), "Ваш номер сохранён. Скоро с вами свяжутся.", null);
        } else {
            executeMessage(telegramBot, update.message().from().id(), "Вы ошиблись вводя номер. Попробуйте ещё раз.", null);
        }
    }

    @Override
    public void executeMessage(TelegramBot telegramBot, Long id, String messageText, Menu menu) {
        SendMessage sendMessage;
        try {
            sendMessage = new SendMessage(id, messageText);
            if (menu != null) {
                sendMessage.replyMarkup(botMenuService.getMenu(menu));
            }
        } catch (RuntimeException e) {
            logger.error(e.getMessage());
            messageText = infoService.getMessage("Error");
            sendMessage = new SendMessage(id, messageText);
        }
        telegramBot.execute(sendMessage);
    }

    @Override
    public void executeImageMessage(String caption, TelegramBot telegramBot, CallbackQuery callbackQuery, Menu menu, byte[] Image) {
        SendPhoto sendPhoto;
        try {
            sendPhoto = new SendPhoto(callbackQuery.from().id(), Image);
            sendPhoto.caption(caption);
            if (menu != null) {
                sendPhoto.replyMarkup(botMenuService.getMenu(menu));
            }
            telegramBot.execute(sendPhoto);
        } catch (RuntimeException e) {
            logger.error(e.getMessage());
            SendMessage sendMessage = new SendMessage(callbackQuery.from().id(), infoService.getMessage("Error"));
            telegramBot.execute(sendMessage);
        }
    }

    private void sendMessageToRandomVolunteer(TelegramBot telegramBot, Update update) {
        List<Volunteer> volunteers = volunteerService.getAllVolunteer().stream().toList();
        long volunteerId = volunteers.stream()
                .skip((int)(volunteers.size() * Math.random()))
                .findFirst()
                .get()
                .getVolunteerTelegramId();
        String messageToVolunteer = "Вас вызывает " + update.message().from().firstName() + " из бота FirstTeam Pet Shelter.";
        SendMessage sendMessage = new SendMessage(volunteerId, messageToVolunteer);
        telegramBot.execute(sendMessage);
    }

    private String parseMessageText(User telegramUser, Shelter shelter, String messageText) {
        String result = messageText;
        if (result.contains("{username}")) {
            result = result.replace("{username}", telegramUser.firstName());
        }
        if (result.contains("{usercontact}")) {
            String userPhone = userService.getUserByTelegramId(telegramUser.id()).getUserPhoneNumber();
            result = userPhone != null ? result.replace("{usercontact}", userPhone) : result.replace("usercontact", "нет номера");
        }
        if (result.contains("sheltertype")) {
            if (shelter.getShelterType().equalsIgnoreCase("dog")) {
                result = result.replace("{sheltertype}", "собачий приют");
            } else if(shelter.getShelterType().equalsIgnoreCase("cat")) {
                result = result.replace("sheltertype", "кошачий приют");
            }
        }
        if (result.contains("{sheltername}")) {
            result = result.replace("{sheltername}", shelter.getShelterName());
        }
        return result;
    }
}