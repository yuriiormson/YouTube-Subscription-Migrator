package org.example;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

public class ConfReader {
    private static final String CONFIG_FILE_PATH = "/Users/Yurii_Ormson/Library/Mobile Documents/com~apple~CloudDocs/IdeaProjects/ExportAndImportYouTubeSubscriptions/conf.properties";
    private static Properties readAppDataSearchPropertiesFile() {
        Properties properties = new Properties();

        try (InputStream inputStream = new FileInputStream(CONFIG_FILE_PATH)) {
            properties.load(inputStream);
        } catch (FileNotFoundException e) {
            System.out.println("\u001B[31mERROR\u001B[0m: The \u001B[31mprogramTypeYear.properties\u001B[0m" +
                    " file not found in src/test/resources/ directory.");
            System.out.println("You need to create it from programTypeYear.properties.TEMPLATE file.");
        } catch (IOException e) {
            e.printStackTrace();
        }

        return properties;
    }
}
