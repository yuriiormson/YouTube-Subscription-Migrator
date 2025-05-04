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
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

public class ExportYouTubeSubscriptions {
    private static final List<String> SCOPES = Arrays.asList("https://www.googleapis.com/auth/youtube.readonly");
    private static final JsonFactory JSON_FACTORY = JacksonFactory.getDefaultInstance();
    private static final String APPLICATION_NAME = "YouTube Subscription Exporter";
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

    public static void main(String[] args) throws GeneralSecurityException, IOException {
        try {
            // Initialize YouTube API
            NetHttpTransport httpTransport = GoogleNetHttpTransport.newTrustedTransport();
            YouTube youtubeService = new YouTube.Builder(httpTransport, JSON_FACTORY, getCredentials(httpTransport))
                    .setApplicationName(APPLICATION_NAME)
                    .build();

            // Retrieve subscriptions
            List<String> subscriptions = new ArrayList<>();
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
                    subscriptions.add(channelId);
                }
                nextPageToken = response.getNextPageToken();
            } while (nextPageToken != null);

            // Save to file
            try (FileWriter writer = new FileWriter("subscriptions.txt")) {
                for (String channelId : subscriptions) {
                    writer.write(channelId + "\n");
                }
            }

            System.out.println("Exported " + subscriptions.size() + " subscriptions");
        } catch (Exception e) {
            System.err.println("Error during export: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
