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
import java.security.GeneralSecurityException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

public class ImportYouTubeSubscriptions {
    private static final List<String> SCOPES = Arrays.asList("https://www.googleapis.com/auth/youtube");
    private static final JsonFactory JSON_FACTORY = JacksonFactory.getDefaultInstance();
    private static final String APPLICATION_NAME = "YouTube Subscription Importer";
    private static final int DAILY_LIMIT = 60;
    private static final String COUNT_FILE = "subscription_count.txt"; // File to store subscription counter
    private static final String CONFIG_FILE_PATH = "config.properties";

    // Load configuration from config.properties
    private static Properties loadConfigProperties() {
        Properties properties = new Properties();
        try (InputStream inputStream = new FileInputStream(CONFIG_FILE_PATH)) {
            properties.load(inputStream);
        } catch (FileNotFoundException e) {
            System.err.println("ERROR: The config.properties file not found in the project root directory.");
            System.err.println("You need to create it with 'client_secrets_file=client_secrets.json'.");
            System.exit(1);
        } catch (IOException e) {
            System.err.println("ERROR: Unable to read config.properties file.");
            e.printStackTrace();
            System.exit(1);
        }
        return properties;
    }

    // Get client_secrets file name from configuration
    private static final Properties configProperties = loadConfigProperties();
    private static final String CLIENT_SECRETS_FILE = configProperties.getProperty("client_secrets_file", "client_secrets.json");

    // Authentication setup
    private static Credential getCredentials(NetHttpTransport httpTransport) throws IOException {
        GoogleClientSecrets clientSecrets = GoogleClientSecrets.load(JSON_FACTORY, new FileReader(CLIENT_SECRETS_FILE));
        GoogleAuthorizationCodeFlow flow = new GoogleAuthorizationCodeFlow.Builder(
                httpTransport, JSON_FACTORY, clientSecrets, SCOPES)
                .setAccessType("offline")
                .build();
        LocalServerReceiver receiver = new LocalServerReceiver.Builder().setPort(8888).build();
        return new AuthorizationCodeInstalledApp(flow, receiver).authorize("user");
    }

    // Retrieve list of existing subscriptions
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

    // Inner class to store subscription counter and date
    private static class SubscriptionCounter {
        int count;
        LocalDate date;

        SubscriptionCounter(int count, LocalDate date) {
            this.count = count;
            this.date = date;
        }
    }

    // Load subscription counter and date from file
    private static SubscriptionCounter loadSubscriptionCounter() {
        try (BufferedReader reader = new BufferedReader(new FileReader(COUNT_FILE))) {
            String line = reader.readLine();
            if (line != null) {
                String[] parts = line.split(",");
                LocalDate date = LocalDate.parse(parts[0]);
                int count = Integer.parseInt(parts[1]);
                return new SubscriptionCounter(count, date);
            }
        } catch (FileNotFoundException e) {
            // File does not exist, this is the first run
        } catch (IOException e) {
            System.err.println("ERROR: Unable to read " + COUNT_FILE);
            e.printStackTrace();
        }
        return new SubscriptionCounter(0, LocalDate.now());
    }

    // Save subscription counter and date to file
    private static void saveSubscriptionCounter(int count, LocalDate date) {
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(COUNT_FILE))) {
            writer.write(date + "," + count);
        } catch (IOException e) {
            System.err.println("ERROR: Unable to write to " + COUNT_FILE);
            e.printStackTrace();
        }
    }

    public static void main(String[] args) throws GeneralSecurityException, IOException, InterruptedException {
        // Initialize YouTube API
        NetHttpTransport httpTransport = GoogleNetHttpTransport.newTrustedTransport();
        YouTube youtubeService = new YouTube.Builder(httpTransport, JSON_FACTORY, getCredentials(httpTransport))
                .setApplicationName(APPLICATION_NAME)
                .build();

        // Check subscription limit
        SubscriptionCounter counter = loadSubscriptionCounter();
        LocalDate today = LocalDate.now();

        // Reset counter if the date has changed
        if (!counter.date.equals(today)) {
            counter = new SubscriptionCounter(0, today);
            saveSubscriptionCounter(0, today);
        }

        // Check if daily limit is reached
        if (counter.count >= DAILY_LIMIT) {
            System.out.println("Reached the limit of " + DAILY_LIMIT + " subscriptions today (" + today + "). Continue tomorrow.");
            return;
        }

        // Get list of existing subscriptions
        Set<String> existingSubscriptions = getExistingSubscriptions(youtubeService);

        // Read channel IDs for migration
        List<String> channelIds = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new FileReader("subscriptions.txt"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                channelIds.add(line.trim());
            }
        }

        // Subscribe to new channels
        int subscribedCount = counter.count;
        for (String channelId : channelIds) {
            if (subscribedCount >= DAILY_LIMIT) {
                System.out.println("Reached the limit of " + DAILY_LIMIT + " subscriptions today (" + today + "). Continue tomorrow.");
                break;
            }
            if (existingSubscriptions.contains(channelId)) {
                System.out.println("Channel " + channelId + " is already subscribed. Skipping.");
                continue;
            }
            try {
                Subscription subscription = new Subscription();
                subscription.setSnippet(new com.google.api.services.youtube.model.SubscriptionSnippet()
                        .setResourceId(new com.google.api.services.youtube.model.ResourceId()
                                .setKind("youtube#channel")
                                .setChannelId(channelId)));

                youtubeService.subscriptions().insert(Arrays.asList("snippet"), subscription).execute();
                System.out.println("Subscribed to channel " + channelId);
                subscribedCount++;
                saveSubscriptionCounter(subscribedCount, today); // Update counter after each subscription
                Thread.sleep(5000); // 5-second delay
            } catch (Exception e) {
                System.err.println("Error subscribing to " + channelId + ": " + e.getMessage());
            }
        }

        System.out.println("Subscribed to " + (subscribedCount - counter.count) + " new channels today");
    }
}