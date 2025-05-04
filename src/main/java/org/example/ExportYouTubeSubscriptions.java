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

import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;

public class ExportYouTubeSubscriptions {
    private static final String CLIENT_SECRETS = "example";
    private static final List<String> SCOPES = Arrays.asList("https://www.googleapis.com/auth/youtube.readonly");
    private static final JsonFactory JSON_FACTORY = JacksonFactory.getDefaultInstance();
    private static final String APPLICATION_NAME = "YouTube Subscription Exporter";

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

    public static void main(String[] args) throws GeneralSecurityException, IOException {
        // Ініціалізація YouTube API
        NetHttpTransport httpTransport = GoogleNetHttpTransport.newTrustedTransport();
        YouTube youtubeService = new YouTube.Builder(httpTransport, JSON_FACTORY, getCredentials(httpTransport))
                .setApplicationName(APPLICATION_NAME)
                .build();

        // Отримання підписок
        List<String> subscriptions = new ArrayList<>();
        String nextPageToken = null;

        do {
            YouTube.Subscriptions.List request = youtubeService.subscriptions()
                    .list(Arrays.asList("snippet")) // Зміна: передаємо List<String>
                    .setMine(true)
                    .setMaxResults(50L);

            if (nextPageToken != null) {
                request.setPageToken(nextPageToken);
            }

            SubscriptionListResponse response = request.execute();
            for (Subscription subscription : response.getItems()) {
                String channelId = subscription.getSnippet().getResourceId().getChannelId();
                subscriptions.add(channelId);
            }
            nextPageToken = response.getNextPageToken();
        } while (nextPageToken != null);

        // Збереження у файл
        try (FileWriter writer = new FileWriter("subscriptions.txt")) {
            for (String channelId : subscriptions) {
                writer.write(channelId + "\n");
            }
        }

        System.out.println("Експортовано " + subscriptions.size() + " підписок");
    }
}
