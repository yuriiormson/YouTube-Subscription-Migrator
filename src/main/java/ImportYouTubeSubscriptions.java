import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.extensions.java6.auth.oauth2.AuthorizationCodeInstalledApp;
import com.google.api.client.extensions.jetty.auth.oauth2.LocalServerReceiver;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.googleapis.json.GoogleJsonError;
import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.services.youtube.YouTube;
import com.google.api.services.youtube.model.ResourceId;
import com.google.api.services.youtube.model.Subscription;
import com.google.api.services.youtube.model.SubscriptionListResponse;
import com.google.api.services.youtube.model.SubscriptionSnippet;

import java.io.*;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

public class ImportYouTubeSubscriptions {
    private static final String CLIENT_SECRETS_FILE = "client_secrets.json";
    private static final JsonFactory JSON_FACTORY = JacksonFactory.getDefaultInstance();
    private static final List<String> SCOPES = Arrays.asList("https://www.googleapis.com/auth/youtube");
    private static final String APPLICATION_NAME = "YouTube Subscription Importer";
    private static final String SUBSCRIPTIONS_FILE = "subscriptions.txt";
    private static final String COUNT_FILE = "subscription_count.txt";
    private static final int DAILY_LIMIT = 120;

    static class SubscriptionCounter {
        LocalDate exportDate;
        int totalSubscriptions;
        LocalDate importDate;
        int totalImported;
        int dailyCount;

        SubscriptionCounter(LocalDate exportDate, int totalSubscriptions, LocalDate importDate, int totalImported, int dailyCount) {
            this.exportDate = exportDate;
            this.totalSubscriptions = totalSubscriptions;
            this.importDate = importDate;
            this.totalImported = totalImported;
            this.dailyCount = dailyCount;
        }
    }

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

        // Import subscriptions
        importSubscriptions(youtubeService);
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

    private static void importSubscriptions(YouTube youtubeService) throws IOException {
        // Check subscription limit
        SubscriptionCounter counter = loadSubscriptionCounter();
        LocalDate today = LocalDate.now();

        // Reset daily count if the date has changed
        if (!counter.importDate.equals(today)) {
            counter.importDate = today;
            counter.dailyCount = 0;
            saveSubscriptionCounter(counter);
        }

        // Check if daily limit is reached
        if (counter.dailyCount >= DAILY_LIMIT) {
            System.out.println("Reached the limit of " + DAILY_LIMIT + " subscriptions today (" + today + "). Continue tomorrow.");
            return;
        }

        // Read channel IDs from subscriptions.txt
        List<String> channelIds;
        try (BufferedReader reader = new BufferedReader(new FileReader(SUBSCRIPTIONS_FILE))) {
            channelIds = reader.lines().toList();
        }

        // Get the current subscriptions in the target account
        Set<String> existingSubscriptions = getExistingSubscriptions(youtubeService);
        counter.totalImported = existingSubscriptions.size(); // Update totalImported based on actual subscriptions
        saveSubscriptionCounter(counter);

        // Calculate remaining subscriptions to import
        Set<String> sourceChannels = new HashSet<>(channelIds);
        sourceChannels.removeAll(existingSubscriptions);
        int remainingSubscriptions = sourceChannels.size();

        // Import subscriptions
        int remainingDaily = DAILY_LIMIT - counter.dailyCount;
        int toImport = Math.min(remainingDaily, remainingSubscriptions);
        int processedToday = 0;

        for (String channelId : channelIds) {
            if (existingSubscriptions.contains(channelId)) {
                continue; // Skip already subscribed channels
            }
            if (processedToday >= toImport) {
                break; // Stop if we've reached the daily limit
            }
            try {
                subscribeToChannel(youtubeService, channelId);
                counter.dailyCount++;
                counter.totalImported++;
                processedToday++;
                existingSubscriptions.add(channelId);
                saveSubscriptionCounter(counter);
                System.out.println("Subscribed to channel: " + channelId + " (" + counter.totalImported + "/" + counter.totalSubscriptions + ")");
            } catch (GoogleJsonResponseException e) {
                counter.dailyCount++; // Increment for every processed channel
                processedToday++;
                if (e.getStatusCode() == 400) {
                    GoogleJsonError error = e.getDetails();
                    if (error != null && error.getErrors() != null) {
                        for (GoogleJsonError.ErrorInfo errorInfo : error.getErrors()) {
                            if ("subscriptionDuplicate".equals(errorInfo.getReason())) {
                                System.out.println("Skipped duplicate subscription for channel: " + channelId);
                                counter.totalImported++;
                                existingSubscriptions.add(channelId);
                                saveSubscriptionCounter(counter);
                                break;
                            }
                        }
                    } else {
                        System.err.println("Error subscribing to " + channelId + ": " + e.getMessage());
                    }
                } else if (e.getStatusCode() == 403) {
                    GoogleJsonError error = e.getDetails();
                    if (error != null && error.getErrors() != null) {
                        for (GoogleJsonError.ErrorInfo errorInfo : error.getErrors()) {
                            if ("quotaExceeded".equals(errorInfo.getReason())) {
                                remainingSubscriptions = counter.totalSubscriptions - counter.totalImported;
                                System.out.println("Quota exceeded. Please wait until your API quota resets (typically after 24 hours).");
                                System.out.println("Current progress: Imported " + counter.totalImported + " out of " + counter.totalSubscriptions + " subscriptions.");
                                System.out.println("Remaining subscriptions to import: " + remainingSubscriptions);
                                saveSubscriptionCounter(counter);
                                System.exit(0); // Terminate the program
                            }
                        }
                    }
                    System.err.println("Error subscribing to " + channelId + ": " + e.getMessage());
                } else {
                    System.err.println("Error subscribing to " + channelId + ": " + e.getMessage());
                }
            } catch (Exception e) {
                System.err.println("Unexpected error subscribing to " + channelId + ": " + e.getMessage());
            }
            try {
                Thread.sleep(5000); // 5-second delay to avoid rate limits
            } catch (InterruptedException e) {
                System.err.println("Thread interrupted during delay: " + e.getMessage());
                Thread.currentThread().interrupt(); // Restore interrupted status
            }
        }

        remainingSubscriptions = counter.totalSubscriptions - counter.totalImported;
        System.out.println("Imported " + counter.dailyCount + " subscriptions today. Total imported: " + counter.totalImported + " out of " + counter.totalSubscriptions);
        System.out.println("Remaining subscriptions to import: " + remainingSubscriptions);
        saveSubscriptionCounter(counter); // Ensure final state is saved
    }

    private static Set<String> getExistingSubscriptions(YouTube youtubeService) throws IOException {
        Set<String> channelIds = new HashSet<>();
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
                channelIds.add(channelId);
            }
            nextPageToken = response.getNextPageToken();
        } while (nextPageToken != null);

        return channelIds;
    }

    private static void subscribeToChannel(YouTube youtubeService, String channelId) throws IOException {
        ResourceId resourceId = new ResourceId();
        resourceId.setKind("youtube#channel");
        resourceId.setChannelId(channelId);

        SubscriptionSnippet snippet = new SubscriptionSnippet();
        snippet.setResourceId(resourceId);

        Subscription subscription = new Subscription();
        subscription.setSnippet(snippet);

        youtubeService.subscriptions().insert(Arrays.asList("snippet"), subscription).execute();
    }

    private static SubscriptionCounter loadSubscriptionCounter() {
        LocalDate exportDate = LocalDate.now();
        int totalSubscriptions = 0;
        LocalDate importDate = LocalDate.now();
        int totalImported = 0;
        int dailyCount = 0;

        try (BufferedReader reader = new BufferedReader(new FileReader(COUNT_FILE))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("Export Date: ")) {
                    exportDate = LocalDate.parse(line.split(": ")[1]);
                } else if (line.startsWith("Total Subscriptions in Source Account: ")) {
                    totalSubscriptions = Integer.parseInt(line.split(": ")[1]);
                } else if (line.startsWith("Last Import Date: ")) {
                    importDate = LocalDate.parse(line.split(": ")[1]);
                } else if (line.startsWith("Total Subscriptions Imported: ")) {
                    totalImported = Integer.parseInt(line.split(": ")[1]);
                } else if (line.startsWith("Daily Import Count: ")) {
                    dailyCount = Integer.parseInt(line.split(": ")[1]);
                }
            }
        } catch (FileNotFoundException e) {
            System.out.println("subscription_count.txt not found, starting fresh.");
        } catch (IOException e) {
            System.err.println("ERROR: Unable to read " + COUNT_FILE);
            e.printStackTrace();
        }
        return new SubscriptionCounter(exportDate, totalSubscriptions, importDate, totalImported, dailyCount);
    }

    private static void saveSubscriptionCounter(SubscriptionCounter counter) {
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(COUNT_FILE))) {
            writer.write("# Subscription Migration Progress\n");
            writer.write("Export Date: " + counter.exportDate + "\n");
            writer.write("Total Subscriptions in Source Account: " + counter.totalSubscriptions + "\n");
            writer.write("Last Import Date: " + counter.importDate + "\n");
            writer.write("Total Subscriptions Imported: " + counter.totalImported + "\n");
            writer.write("Daily Import Count: " + counter.dailyCount + "\n");
        } catch (IOException e) {
            System.err.println("ERROR: Unable to write to " + COUNT_FILE);
            e.printStackTrace();
        }
    }
}