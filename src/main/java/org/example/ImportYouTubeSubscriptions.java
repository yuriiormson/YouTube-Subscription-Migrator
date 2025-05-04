package org.example;

import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.extensions.java6.auth.oauth2.AuthorizationCodeInstalledApp;
import com.google.api.client.extensions.jetty.auth.oauth2.LocalServerReceiver;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.services.youtube.YouTube;
import com.google.api.services.youtube.model.Subscription;
import com.google.api.services.youtube.model.SubscriptionListResponse;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class ImportYouTubeSubscriptions {
    private static final String CLIENT_SECRETS = "client_secret_167862215707-csctad2bgacp250nonie3pkraifa3kg9.apps.googleusercontent.com.json";
    private static final List<String> SCOPES = Arrays.asList("https://www.googleapis.com/auth/youtube");
    private static final JsonFactory JSON_FACTORY = JacksonFactory.getDefaultInstance();
    private static final String APPLICATION_NAME = "YouTube Subscription Importer";
    private static final int DAILY_LIMIT = 60;

    // Налаштування автентифікації
    private static Credential getCredentials(NetHttpTransport httpTransport) throws IOException {
        GoogleClientSecrets clientSecrets = GoogleClientSecrets.load(JSON_FACTORY, new FileReader(CLIENT_SECRETS));
        GoogleAuthorizationCodeFlow flow = new GoogleAuthorizationCodeFlow.Builder(
                httpTransport, JSON_FACTORY, clientSecrets, SCOPES)
                .setAccessType("offline")
                .build();
        LocalServerReceiver receiver = new LocalServerReceiver.Builder().setPort(8888).build();
        return new AuthorizationCodeInstalledApp(flow, receiver).authorize("user");
    }

    // Отримує список ID каналів, на які вже підписаний акаунт
    private static Set<String> getExistingSubscriptions(YouTube youtubeService) throws IOException {
        Set<String> existingSubscriptions = new HashSet<>();
        String nextPageToken = null;

        do {
            YouTube.Subscriptions.List request = youtubeService.subscriptions()
                    .list(Arrays.asList("snippet"))
                    .setMine(true)
                    .setMaxResults(50L);

            if (nextPageToken != null) {
                request.setPageToken(nextPageToken);
            }

            SubscriptionListResponse response = request.execute();
            for (Subscription subscription : response.getItems()) {
                String channelId = subscription.getSnippet().getResourceId().getChannelId();
                existingSubscriptions.add(channelId);
            }
            nextPageToken = response.getNextPageToken();
        } while (nextPageToken != null);

        return existingSubscriptions;
    }

    public static void main(String[] args) throws GeneralSecurityException, IOException, InterruptedException {
        // Ініціалізація YouTube API
        NetHttpTransport httpTransport = GoogleNetHttpTransport.newTrustedTransport();
        YouTube youtubeService = new YouTube.Builder(httpTransport, JSON_FACTORY, getCredentials(httpTransport))
                .setApplicationName(APPLICATION_NAME)
                .build();

        // Отримуємо список уже існуючих підписок нового акаунта
        Set<String> existingSubscriptions = getExistingSubscriptions(youtubeService);

        // Читання ID каналів для перенесення
        List<String> channelIds = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new FileReader("subscriptions.txt"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                channelIds.add(line.trim());
            }
        }

        // Підписка на нові канали
        int subscribedCount = 0;
        for (String channelId : channelIds) {
            if (subscribedCount >= DAILY_LIMIT) {
                System.out.println("Досягнуто ліміт " + DAILY_LIMIT + " на сьогодні. Продовжіть завтра.");
                break;
            }
            if (existingSubscriptions.contains(channelId)) {
                System.out.println("Канал " + channelId + " уже підписаний. Пропускаємо.");
                continue;
            }
            try {
                Subscription subscription = new Subscription();
                subscription.setSnippet(new com.google.api.services.youtube.model.SubscriptionSnippet()
                        .setResourceId(new com.google.api.services.youtube.model.ResourceId()
                                .setKind("youtube#channel")
                                .setChannelId(channelId)));

                youtubeService.subscriptions().insert(Arrays.asList("snippet"), subscription).execute(); // Зміна: передаємо List<String>
                System.out.println("Підписано на канал " + channelId);
                subscribedCount++;
                Thread.sleep(10000); // Затримка 1 секунда
            } catch (Exception e) {
                System.err.println("Помилка при підписці на " + channelId + ": " + e.getMessage());
            }
        }

        System.out.println("Підписано на " + subscribedCount + " нових каналів сьогодні");
    }
}
