/**
 * Monument Photo Exporter - Main Entry Point
 * 
 * Command line tool to export photos from Monument photo management system
 * to an organized folder structure with optional EXIF metadata preservation.
 * 
 * Compatible with Apache Commons Imaging 1.0-alpha3
 */
public class Main {

    /**
     * Print usage information and exit
     */
    private static void printUsage() {
        System.out.println("Monument Photo Exporter v0.11");
        System.out.println();
        System.out.println("Usage: java -jar MonumentPhotoExporter.jar [OPTIONS]");
        System.out.println();
        System.out.println("Required Arguments:");
        System.out.println("  --source <path>           Path to Monument source directory");
        System.out.println("  --dest <path>             Path to destination export directory");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  --flatten                 Flatten folder structure (single level per user)");
        System.out.println("  --save-edits              Export edited versions of photos");
        System.out.println("  --save-comments           Write photo captions to EXIF metadata");
        System.out.println("  --export-gps              Write GPS coordinates to EXIF metadata");
        System.out.println("  --export-tags             Write tags to EXIF metadata");
        System.out.println("  --tags-as-folders         Create additional copies in tag-based folders");
        System.out.println("  --dryrun                  Simulate export without copying files");
        System.out.println("  --help                    Show this help message");
        System.out.println();
        System.out.println("Examples:");
        System.out.println("  # Basic export preserving folder structure");
        System.out.println("  java -jar MonumentPhotoExporter.jar --source /mnt/monument --dest /backup/photos");
        System.out.println();
        System.out.println("  # Flatten structure and export with all metadata");
        System.out.println("  java -jar MonumentPhotoExporter.jar --source /mnt/monument --dest /backup/photos \\");
        System.out.println("       --flatten --save-comments --export-gps --export-tags");
        System.out.println();
        System.out.println("  # Export with edited versions and tag folders");
        System.out.println("  java -jar MonumentPhotoExporter.jar --source /mnt/monument --dest /backup/photos \\");
        System.out.println("       --save-edits --tags-as-folders --save-comments");
        System.out.println();
        System.out.println("  # Dry run to see what would be exported");
        System.out.println("  java -jar MonumentPhotoExporter.jar --source /mnt/monument --dest /backup/photos \\");
        System.out.println("       --dryrun --flatten");
        System.out.println();
    }

    /**
     * Main entry point
     * @param args Command line arguments
     */
    public static void main(String[] args) {
        // Check for help flag
        if (args.length == 0 || containsArg(args, "--help") || containsArg(args, "-h")) {
            printUsage();
            System.exit(0);
        }

        // Parse arguments
        RunArguments runArgs = new RunArguments();
        
        try {
            // Required arguments
            runArgs.sourceDir = getArgValue(args, "--source");
            runArgs.destinationDir = getArgValue(args, "--dest");
            
            if (runArgs.sourceDir == null || runArgs.sourceDir.isEmpty()) {
                System.err.println("ERROR: --source argument is required");
                System.err.println();
                printUsage();
                System.exit(1);
            }
            
            if (runArgs.destinationDir == null || runArgs.destinationDir.isEmpty()) {
                System.err.println("ERROR: --dest argument is required");
                System.err.println();
                printUsage();
                System.exit(1);
            }
            
            // Optional flags
            runArgs.flatten = containsArg(args, "--flatten");
            runArgs.saveEdits = containsArg(args, "--save-edits");
            runArgs.saveComments = containsArg(args, "--save-comments");
            runArgs.exportGps = containsArg(args, "--export-gps");
            runArgs.exportTags = containsArg(args, "--export-tags");
            runArgs.tagsAsFolders = containsArg(args, "--tags-as-folders");
            runArgs.dryrun = containsArg(args, "--dryrun");
            
        } catch (Exception e) {
            System.err.println("ERROR: Failed to parse arguments: " + e.getMessage());
            System.err.println();
            printUsage();
            System.exit(1);
        }
        
        // Validate source directory exists
        java.io.File sourceFile = new java.io.File(runArgs.sourceDir);
        if (!sourceFile.exists()) {
            System.err.println("ERROR: Source directory does not exist: " + runArgs.sourceDir);
            System.exit(1);
        }
        
        if (!sourceFile.isDirectory()) {
            System.err.println("ERROR: Source path is not a directory: " + runArgs.sourceDir);
            System.exit(1);
        }
        
        // Check for Monument database
        java.io.File dbFile = new java.io.File(runArgs.sourceDir + "/monument/.userdata/m.sqlite3");
        if (!dbFile.exists()) {
            System.err.println("ERROR: Monument database not found at: " + dbFile.getAbsolutePath());
            System.err.println("       Make sure the source directory is the root of a Monument installation");
            System.exit(1);
        }
        
        // Create destination directory if it doesn't exist (unless dry run)
        if (!runArgs.dryrun) {
            java.io.File destFile = new java.io.File(runArgs.destinationDir);
            if (!destFile.exists()) {
                System.out.println("INFO: Creating destination directory: " + runArgs.destinationDir);
                if (!destFile.mkdirs()) {
                    System.err.println("ERROR: Failed to create destination directory: " + runArgs.destinationDir);
                    System.exit(1);
                }
            }
        }
        
        // Print configuration
        System.out.println("================================================");
        System.out.println(" Monument Photo Exporter v0.11");
        System.out.println("================================================");
        System.out.println();
        System.out.println("Configuration:");
        System.out.println("  Source      : " + runArgs.sourceDir);
        System.out.println("  Destination : " + runArgs.destinationDir);
        System.out.println();
        System.out.println("Options:");
        System.out.println("  Flatten structure    : " + (runArgs.flatten ? "YES" : "NO"));
        System.out.println("  Export edited files  : " + (runArgs.saveEdits ? "YES" : "NO"));
        System.out.println("  Save comments to EXIF: " + (runArgs.saveComments ? "YES" : "NO"));
        System.out.println("  Export GPS to EXIF   : " + (runArgs.exportGps ? "YES" : "NO"));
        System.out.println("  Export tags to EXIF  : " + (runArgs.exportTags ? "YES" : "NO"));
        System.out.println("  Tags as folders      : " + (runArgs.tagsAsFolders ? "YES" : "NO"));
        System.out.println("  Dry run mode         : " + (runArgs.dryrun ? "YES" : "NO"));
        System.out.println();
        System.out.println("================================================");
        System.out.println();
        
        if (runArgs.dryrun) {
            System.out.println("DRY RUN MODE: No files will be copied");
            System.out.println();
        }
        
        // Initialize and run exporter
        try {
            Exporter exporter = new Exporter(runArgs);
            exporter.init();
            
            System.out.println("Starting export...");
            System.out.println();
            
            exporter.export();
            
        } catch (Exception e) {
            System.err.println();
            System.err.println("FATAL ERROR: Export failed");
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
    
    /**
     * Check if an argument flag exists in the arguments array
     * @param args Command line arguments
     * @param flag Flag to search for (e.g., "--flatten")
     * @return true if flag exists, false otherwise
     */
    private static boolean containsArg(String[] args, String flag) {
        for (String arg : args) {
            if (arg.equalsIgnoreCase(flag)) {
                return true;
            }
        }
        return false;
    }
    
    /**
     * Get the value following an argument flag
     * @param args Command line arguments
     * @param flag Flag to search for (e.g., "--source")
     * @return Value following the flag, or null if not found
     */
    private static String getArgValue(String[] args, String flag) {
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equalsIgnoreCase(flag)) {
                return args[i + 1];
            }
        }
        return null;
    }
}