package com.puppet.supportbundleassistant;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.rag.DefaultRetrievalAugmentor;
import dev.langchain4j.rag.RetrievalAugmentor;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Scanner;

public class SupportBundleAssistant {
    private static final Logger logger = LoggerFactory.getLogger(SupportBundleAssistant.class);

    private final TextFileIndexer fileIndexer;
    private final ChatAssistant chatAssistant;
    private final EmbeddingStore<TextSegment> embeddingStore;
    private final EmbeddingModel embeddingModel;

    interface ChatAssistant {
        @SystemMessage(fromResource = "/system-message.md")
        String chat(String message);
    }

    public SupportBundleAssistant() {
        String openAiApiKey = System.getenv("OPENAI_API_KEY");
        if (openAiApiKey == null || openAiApiKey.isEmpty()) {
            throw new IllegalStateException("OPENAI_API_KEY environment variable must be set");
        }

        // Initialize embedding model and store
        this.embeddingModel = OpenAiEmbeddingModel.builder()
            .apiKey(openAiApiKey)
            .modelName("text-embedding-3-small")
            .build();

        this.embeddingStore = new InMemoryEmbeddingStore<>();

        // Initialize file indexer
        this.fileIndexer = new TextFileIndexer(embeddingModel, embeddingStore);

        // Initialize chat model
        ChatModel chatModel = OpenAiChatModel.builder()
            .apiKey(openAiApiKey)
            .modelName("gpt-4o-mini")
            .temperature(0.7)
            .build();

        // Setup content retriever
        ContentRetriever contentRetriever = EmbeddingStoreContentRetriever.builder()
            .embeddingStore(embeddingStore)
            .embeddingModel(embeddingModel)
            .maxResults(5)
            .minScore(0.6)
            .build();

        // Setup RAG
        RetrievalAugmentor retrievalAugmentor = DefaultRetrievalAugmentor.builder()
            .contentRetriever(contentRetriever)
            .build();

        // Initialize chat assistant
        this.chatAssistant = AiServices.builder(ChatAssistant.class)
            .chatModel(chatModel)
            .retrievalAugmentor(retrievalAugmentor)
            .build();
    }

    public static void main(String[] args) {
        try {
            SupportBundleAssistant app = new SupportBundleAssistant();
            app.run(args);
        } catch (Exception e) {
            logger.error("Application failed to start", e);
            System.exit(1);
        }
    }

    public void run(String[] args) throws IOException {
        System.out.println("=== Support Bundle Chat Assistant ===");
        System.out.println();

        // Index files if directory path provided
        if (args.length > 0) {
            String directoryPath = args[0];
            indexFiles(directoryPath);
        } else {
            System.out.println("No directory specified. You can index text files using the 'index <path>' command.");
        }

        // Start interactive chat
        startInteractiveChat();
    }

    private void indexFiles(String directoryPath) throws IOException {
        System.out.println("Indexing text files from: " + directoryPath);
        Path path = Paths.get(directoryPath);

        long startTime = System.currentTimeMillis();
        int indexedCount = fileIndexer.indexDirectory(path);
        long endTime = System.currentTimeMillis();

        System.out.printf("Successfully indexed %d text files in %.2f seconds%n",
            indexedCount, (endTime - startTime) / 1000.0);
        System.out.println();
    }

    private void startInteractiveChat() {
        Scanner scanner = new Scanner(System.in);

        System.out.println("Commands:");
        System.out.println("  - 'index <path>': Index files from a support bundle directory");
        System.out.println("  - 'status': Show indexing status");
        System.out.println("  - 'cache clear': Clear embedding cache");
        System.out.println("  - 'cache stats': Show cache statistics");
        System.out.println("  - 'quit' or 'exit': Exit the application");
        System.out.println();

        while (true) {
            System.out.print("# ");
            String input = scanner.nextLine().trim();

            if (input.isEmpty()) {
                continue;
            }

            if (input.equalsIgnoreCase("quit") || input.equalsIgnoreCase("exit")) {
                System.out.println("Goodbye!");
                break;
            }

            if (input.toLowerCase().startsWith("index ")) {
                String path = input.substring(6).trim();
                try {
                    indexFiles(path);
                } catch (IOException e) {
                    System.out.println("Error indexing text files: " + e.getMessage());
                }
                continue;
            }

            if (input.equalsIgnoreCase("status")) {
                showStatus();
                continue;
            }

            if (input.equalsIgnoreCase("cache clear")) {
                fileIndexer.clearCache();
                System.out.println("Cache cleared successfully.");
                System.out.println();
                continue;
            }

            if (input.equalsIgnoreCase("cache stats")) {
                System.out.println("Cache Statistics:");
                System.out.println("  - " + fileIndexer.getCacheStats());
                System.out.println();
                continue;
            }

            // Process chat query
            try {
                SpinnerThread spinner = new SpinnerThread();
                spinner.start();
                String response = chatAssistant.chat(input);
                spinner.stopSpinner();
                System.out.print("\r"); // Clear spinner line
                System.out.println(response);
                System.out.println();
            } catch (Exception e) {
                System.out.println("Error processing your question: " + e.getMessage());
                logger.error("Chat error", e);
                System.out.println();
            }
        }

        scanner.close();
    }

    private void showStatus() {
        int totalSegments = fileIndexer.getTotalSegmentCount();

        System.out.println("Index Status:");
        System.out.println("  - Total text segments: " + totalSegments);
        System.out.println("  - Text files indexed: " + fileIndexer.getIndexedFileCount());
        System.out.println("  - " + fileIndexer.getCacheStats());
        System.out.println();
    }

    /**
     * Simple spinner thread for showing processing status
     */
    private static class SpinnerThread extends Thread {
        private volatile boolean running = true;
        private final String[] spinnerChars = {"●", "○", "◐", "◑", "◒", "◓"};

        @Override
        public void run() {
            int i = 0;
            while (running) {
                System.out.print("\rThinking " + spinnerChars[i % spinnerChars.length] + " ");
                System.out.flush();
                i++;
                try {
                    Thread.sleep(200); // Update every 200ms
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        public void stopSpinner() {
            running = false;
            try {
                this.join(500); // Wait up to 500ms for thread to finish
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
