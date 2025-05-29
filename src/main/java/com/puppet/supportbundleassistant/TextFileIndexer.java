package com.puppet.supportbundleassistant;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.data.document.loader.FileSystemDocumentLoader;

import org.apache.commons.io.FilenameUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Handles text file discovery, processing, and indexing for the chat assistant.
 * Supports various text file formats and handles large files by chunking them appropriately.
 */
public class TextFileIndexer {
    private static final Logger logger = LoggerFactory.getLogger(TextFileIndexer.class);

    // File size thresholds
    private static final long LARGE_FILE_THRESHOLD = 10 * 1024 * 1024; // 10MB
    private static final long MAX_FILE_SIZE = 100 * 1024 * 1024; // 100MB

    // Chunk sizes for different file types
    private static final int DEFAULT_CHUNK_SIZE = 1000;
    private static final int LARGE_FILE_CHUNK_SIZE = 2000;
    private static final int CHUNK_OVERLAP = 200;

    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;
    private final AtomicInteger indexedFileCount = new AtomicInteger(0);
    private final AtomicInteger totalSegmentCount = new AtomicInteger(0);

    // Cache directory and file tracking
    private final Path cacheDir = Paths.get(System.getProperty("user.home"), ".supportbundle-cache");
    private final Map<String, Long> fileHashes = new HashMap<>();
    private final Set<String> indexedFiles = new HashSet<>();

    public TextFileIndexer(EmbeddingModel embeddingModel, EmbeddingStore<TextSegment> embeddingStore) {
        this.embeddingModel = embeddingModel;
        this.embeddingStore = embeddingStore;

        // Create cache directory and load cache index
        try {
            Files.createDirectories(cacheDir);
            loadCacheIndex();
        } catch (IOException e) {
            logger.warn("Failed to create cache directory: {}", cacheDir, e);
        }
    }

    /**
     * Index all supported text files in a directory recursively.
     */
    public int indexDirectory(Path directoryPath) throws IOException {
        if (!Files.exists(directoryPath)) {
            throw new IOException("Directory does not exist: " + directoryPath);
        }

        if (!Files.isDirectory(directoryPath)) {
            throw new IOException("Path is not a directory: " + directoryPath);
        }

        AtomicInteger processedCount = new AtomicInteger(0);

        Files.walkFileTree(directoryPath, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                try {
                    if (shouldProcessFile(file, attrs)) {
                        indexTextFile(file);
                        processedCount.incrementAndGet();

                        if (processedCount.get() % 10 == 0) {
                            System.out.print(".");
                            System.out.flush();
                        }
                    }
                } catch (Exception e) {
                    logger.warn("Failed to process text file: {}", file, e);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                logger.warn("Failed to visit text file: {}", file, exc);
                return FileVisitResult.CONTINUE;
            }
        });

        if (processedCount.get() > 0) {
            System.out.println(); // New line after progress dots
        }

        return processedCount.get();
    }

    /**
     * Index a single text file.
     */
    public void indexTextFile(Path filePath) throws IOException {
        logger.info("Indexing text file: {}", filePath);

        // Check if file is already cached and unchanged
        String fileKey = getFileKey(filePath);
        long currentModified = Files.getLastModifiedTime(filePath).toMillis();

        if (fileHashes.containsKey(fileKey) && fileHashes.get(fileKey) == currentModified) {
            // Try to load from cache
            if (loadFromCache(fileKey, filePath)) {
                logger.info("Loaded cached embeddings for: {}", filePath.getFileName());
                indexedFileCount.incrementAndGet();
                indexedFiles.add(filePath.toString()); // Track indexed files
                return;
            }
        }

        try {
            // Load document using FileSystemDocumentLoader
            Document document = FileSystemDocumentLoader.loadDocument(filePath);

            // Add file metadata
            document.metadata().put("file_path", filePath.toString());
            document.metadata().put("file_name", filePath.getFileName().toString());
            document.metadata().put("file_size", String.valueOf(Files.size(filePath)));

            // Choose appropriate chunk size based on file size
            int chunkSize = Files.size(filePath) > LARGE_FILE_THRESHOLD
                ? LARGE_FILE_CHUNK_SIZE
                : DEFAULT_CHUNK_SIZE;

            // Create splitter with appropriate chunk size
            DocumentSplitter splitter = DocumentSplitters.recursive(chunkSize, CHUNK_OVERLAP);

            // Split document into segments
            List<TextSegment> segments = splitter.split(document);

            logger.info("Split text file {} into {} segments", filePath.getFileName(), segments.size());

            // Process segments in batches to avoid memory issues
            procesSegmentsInBatches(segments, filePath.toString());

            // Cache the results
            saveToCache(fileKey, segments, filePath);
            fileHashes.put(fileKey, currentModified);
            saveCacheIndex();

            indexedFileCount.incrementAndGet();
            indexedFiles.add(filePath.toString()); // Track indexed files

        } catch (Exception e) {
            logger.error("Failed to index text file: {}", filePath, e);
            throw new IOException("Failed to index text file: " + filePath, e);
        }
    }

    private void procesSegmentsInBatches(List<TextSegment> segments, String filePath) {
        final int BATCH_SIZE = 20; // Reduced batch size for better performance

        for (int i = 0; i < segments.size(); i += BATCH_SIZE) {
            int endIndex = Math.min(i + BATCH_SIZE, segments.size());
            List<TextSegment> batch = segments.subList(i, endIndex);

            try {
                // Generate embeddings for batch
                Response<List<Embedding>> response = embeddingModel.embedAll(batch);
                List<Embedding> embeddings = response.content();

                // Store embeddings with segments
                for (int j = 0; j < batch.size(); j++) {
                    TextSegment segment = batch.get(j);
                    Embedding embedding = embeddings.get(j);

                    // Add segment metadata
                    segment.metadata().put("file_path", filePath);
                    segment.metadata().put("segment_index", String.valueOf(i + j));

                    embeddingStore.add(embedding, segment);
                    totalSegmentCount.incrementAndGet();
                }

                logger.debug("Processed batch {}-{} for file {}", i, endIndex - 1, filePath);

            } catch (Exception e) {
                logger.error("Failed to process batch {}-{} for file {}", i, endIndex - 1, filePath, e);
            }
        }
    }

    /**
     * Determine if a text file should be processed based on its attributes.
     */
    private boolean shouldProcessFile(Path file, BasicFileAttributes attrs) {
        // Skip if not a regular file
        if (!attrs.isRegularFile()) {
            return false;
        }

        // Skip if file is too large
        if (attrs.size() > MAX_FILE_SIZE) {
            logger.warn("Skipping large text file ({}MB): {}",
                attrs.size() / (1024 * 1024), file);
            return false;
        }

        // Skip if file is empty
        if (attrs.size() == 0) {
            return false;
        }

        // Check file extension
        String fileName = file.getFileName().toString().toLowerCase();
        return isSupportedTextFileType(fileName);
    }

    /**
     * Check if file type is supported for text processing.
     */
    private boolean isSupportedTextFileType(String fileName) {
        List<String> validExtensions = List.of("txt", "json", "log");
        if(validExtensions.contains(FilenameUtils.getExtension(fileName))) {
            return true;
        } else {
            return false;
        }
    }

    public int getIndexedFileCount() {
        return indexedFileCount.get();
    }

    public int getTotalSegmentCount() {
        return totalSegmentCount.get();
    }

    public Set<String> getIndexedFiles() {
        return new HashSet<>(indexedFiles);
    }

    /**
     * Generate a unique key for a file based on its path and name
     */
    private String getFileKey(Path filePath) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            md.update(filePath.toString().getBytes());
            byte[] digest = md.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // Fallback to simple hash
            return String.valueOf(filePath.toString().hashCode());
        }
    }

    /**
     * Load cache index from disk
     */
    private void loadCacheIndex() {
        Path indexFile = cacheDir.resolve("cache-index.txt");
        if (!Files.exists(indexFile)) {
            return;
        }

        try (BufferedReader reader = Files.newBufferedReader(indexFile)) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split("=");
                if (parts.length == 2) {
                    fileHashes.put(parts[0], Long.parseLong(parts[1]));
                }
            }
            logger.info("Loaded cache index with {} entries", fileHashes.size());
        } catch (IOException | NumberFormatException e) {
            logger.warn("Failed to load cache index", e);
            fileHashes.clear();
        }
    }

    /**
     * Save cache index to disk
     */
    private void saveCacheIndex() {
        Path indexFile = cacheDir.resolve("cache-index.txt");
        try (BufferedWriter writer = Files.newBufferedWriter(indexFile)) {
            for (Map.Entry<String, Long> entry : fileHashes.entrySet()) {
                writer.write(entry.getKey() + "=" + entry.getValue());
                writer.newLine();
            }
        } catch (IOException e) {
            logger.warn("Failed to save cache index", e);
        }
    }

    /**
     * Load embeddings from cache
     */
    private boolean loadFromCache(String fileKey, Path filePath) {
        Path cacheFile = cacheDir.resolve(fileKey + ".cache");
        if (!Files.exists(cacheFile)) {
            return false;
        }

        try (ObjectInputStream ois = new ObjectInputStream(Files.newInputStream(cacheFile))) {
            @SuppressWarnings("unchecked")
            List<CachedSegment> cachedSegments = (List<CachedSegment>) ois.readObject();

            // Restore segments to embedding store
            for (CachedSegment cached : cachedSegments) {
                TextSegment segment = TextSegment.from(cached.text);
                // Add metadata entries
                for (Map.Entry<String, String> entry : cached.metadata.entrySet()) {
                    segment.metadata().put(entry.getKey(), entry.getValue());
                }

                Embedding embedding = new Embedding(cached.vector);
                embeddingStore.add(embedding, segment);
                totalSegmentCount.incrementAndGet();
            }

            return true;
        } catch (IOException | ClassNotFoundException e) {
            logger.warn("Failed to load cache for {}: {}", filePath.getFileName(), e.getMessage());
            // Delete corrupted cache file
            try {
                Files.deleteIfExists(cacheFile);
            } catch (IOException deleteException) {
                logger.warn("Failed to delete corrupted cache file", deleteException);
            }
            return false;
        }
    }

    /**
     * Save embeddings to cache
     */
    private void saveToCache(String fileKey, List<TextSegment> segments, Path filePath) {
        Path cacheFile = cacheDir.resolve(fileKey + ".cache");

        try {
            // Generate embeddings and prepare cache data
            List<CachedSegment> cachedSegments = new java.util.ArrayList<>();
            Response<List<Embedding>> response = embeddingModel.embedAll(segments);
            List<Embedding> embeddings = response.content();

            for (int i = 0; i < segments.size(); i++) {
                TextSegment segment = segments.get(i);
                Embedding embedding = embeddings.get(i);

                CachedSegment cached = new CachedSegment();
                cached.text = segment.text();

                // Store basic metadata we know we'll need
                cached.metadata = new HashMap<>();
                cached.metadata.put("file_path", filePath.toString());
                cached.metadata.put("file_name", filePath.getFileName().toString());
                cached.metadata.put("segment_index", String.valueOf(i));

                cached.vector = embedding.vector();
                cachedSegments.add(cached);
            }

            // Save to cache file
            try (ObjectOutputStream oos = new ObjectOutputStream(Files.newOutputStream(cacheFile))) {
                oos.writeObject(cachedSegments);
            }

            logger.debug("Saved {} segments to cache for {}", cachedSegments.size(), filePath.getFileName());
        } catch (IOException e) {
            logger.warn("Failed to save cache for {}: {}", filePath.getFileName(), e.getMessage());
        }
    }

    /**
     * Clear all cached data
     */
    public void clearCache() {
        try {
            if (Files.exists(cacheDir)) {
                Files.walk(cacheDir)
                    .filter(Files::isRegularFile)
                    .forEach(file -> {
                        try {
                            Files.delete(file);
                        } catch (IOException e) {
                            logger.warn("Failed to delete cache file: {}", file, e);
                        }
                    });
            }
            fileHashes.clear();
            logger.info("Cache cleared");
        } catch (IOException e) {
            logger.warn("Failed to clear cache", e);
        }
    }

    /**
     * Get cache statistics
     */
    public String getCacheStats() {
        long cacheSize = 0;
        int fileCount = 0;

        try {
            if (Files.exists(cacheDir)) {
                try (var files = Files.walk(cacheDir)) {
                    var stats = files
                        .filter(Files::isRegularFile)
                        .filter(f -> f.toString().endsWith(".cache"))
                        .mapToLong(f -> {
                            try {
                                return Files.size(f);
                            } catch (IOException e) {
                                return 0;
                            }
                        })
                        .summaryStatistics();

                    cacheSize = stats.getSum();
                    fileCount = (int) stats.getCount();
                }
            }
        } catch (IOException e) {
            logger.warn("Failed to calculate cache stats", e);
        }

        return String.format("Cache: %d files, %.2f MB", fileCount, cacheSize / (1024.0 * 1024.0));
    }

    /**
     * Serializable class for caching segment data
     */
    private static class CachedSegment implements Serializable {
        private static final long serialVersionUID = 1L;
        String text;
        Map<String, String> metadata;
        float[] vector;
    }
}