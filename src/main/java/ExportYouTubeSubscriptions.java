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

import java.io.*;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

public class ExportYouTubeSubscriptions {
    private static final String CLIENT_SECRETS_FILE = "client_secrets.json";
    private static final JsonFactory JSON_FACTORY = JacksonFactory.getDefaultInstance();
    private static final List<String> SCOPES = Arrays.asList("https://www.googleapis.com/auth/youtube.readonly");
    private static final String APPLICATION_NAME = "YouTube Subscription Exporter";
    private static final String SUBSCRIPTIONS_FILE = "subscriptions.txt";
    private static final String COUNT_FILE = "subscription_count.txt";

    public static void main(String[] args) throws Exception {
        // Load client secrets from config
        Properties properties = new Properties();
        try (FileInputStream fis = new FileInputStream("config.properties")) {
            properties.load(fis);
        }
        String clientSecretsFile = properties.getProperty("client_secrets_file", CLIENT_SECRETS_FILE);

        // Set up HTTP transport and YouTube client
        NetHttpTransport httpTransport = GoogleNetHttpTransport.newTrustedTransport();
        Credential credential = getCredentials(httpTransport, clientSecretsFile);
        YouTube youtubeService = new YouTube.Builder(httpTransport, JSON_FACTORY, credential)
                .setApplicationName(APPLICATION_NAME)
                .build();

        // Export subscriptions
        exportSubscriptions(youtubeService);
    }

    private static Credential getCredentials(NetHttpTransport httpTransport, String clientSecretsFile) throws IOException {
        GoogleClientSecrets clientSecrets = GoogleClientSecrets.load(JSON_FACTORY, new FileReader(clientSecretsFile));
        GoogleAuthorizationCodeFlow flow = new GoogleAuthorizationCodeFlow.Builder(
                httpTransport, JSON_FACTORY, clientSecrets, SCOPES)
                .setAccessType("offline")
                .build();
        LocalServerReceiver receiver = new LocalServerReceiver.Builder().setPort(8888).build();
        return new AuthorizationCodeInstalledApp(flow, receiver).authorize("user");
    }

    private static void exportSubscriptions(YouTube youtubeService) throws IOException {
        int totalSubscriptions = 0;
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(SUBSCRIPTIONS_FILE))) {
            String nextPageToken = null;
            do {
                YouTube.Subscriptions.List request = youtubeService.subscriptions()
                        .list(Arrays.asList("snippet")) // Changed to Arrays.asList for correct type
                        .setMine(true)
                        .setMaxResults(50L);
                if (nextPageToken != null) {
                    request.setPageToken(nextPageToken);
                }

                SubscriptionListResponse response = request.execute();
                List<Subscription> subscriptions = response.getItems();
                totalSubscriptions += subscriptions.size();

                for (Subscription subscription : subscriptions) {
                    String channelId = subscription.getSnippet().getResourceId().getChannelId();
                    writer.write(channelId);
                    writer.newLine();
                }

                nextPageToken = response.getNextPageToken();
                try {
                    Thread.sleep(5000); // 5-second delay to avoid rate limits
                } catch (InterruptedException e) {
                    System.err.println("Thread interrupted during delay: " + e.getMessage());
                    Thread.currentThread().interrupt(); // Restore interrupted status
                }
            } while (nextPageToken != null);
        }

        // Save total subscriptions and export date
        LocalDate exportDate = LocalDate.now();
        updateSubscriptionCountFile(exportDate, totalSubscriptions);
        System.out.println("Exported " + totalSubscriptions + " subscriptions to " + SUBSCRIPTIONS_FILE);
    }

    private static void updateSubscriptionCountFile(LocalDate exportDate, int totalSubscriptions) {
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(COUNT_FILE))) {
            writer.write("# Subscription Migration Progress\n");
            writer.write("Export Date: " + exportDate + "\n");
            writer.write("Total Subscriptions in Source Account: " + totalSubscriptions + "\n");
            writer.write("Last Import Date: " + LocalDate.now() + "\n");
            writer.write("Total Subscriptions Imported: 0\n");
            writer.write("Daily Import Count: 0\n");
        } catch (IOException e) {
            System.err.println("ERROR: Unable to write to " + COUNT_FILE);
            e.printStackTrace();
        }
    }
}
