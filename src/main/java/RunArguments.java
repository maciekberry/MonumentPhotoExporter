/**
 * Container class for runtime arguments
 * Holds all configuration options for the Monument Photo Exporter
 */
public class RunArguments {
    
    /** Source directory path (Monument installation root) */
    public String sourceDir;
    
    /** Destination directory path for exported photos */
    public String destinationDir;
    
    /** Flatten folder structure to single level per user */
    public boolean flatten = false;
    
    /** Export edited versions of photos */
    public boolean saveEdits = false;
    
    /** Write photo captions to EXIF ImageDescription field */
    public boolean saveComments = false;
    
    /** Write GPS coordinates to EXIF metadata */
    public boolean exportGps = false;
    
    /** Write tags to EXIF metadata */
    public boolean exportTags = false;
    
    /** Create additional copies of photos organized by tags */
    public boolean tagsAsFolders = false;
    
    /** Dry run mode - simulate without actually copying files */
    public boolean dryrun = false;
    
    /**
     * Default constructor with default values
     */
    public RunArguments() {
        // All fields initialized with default values above
    }
    
    /**
     * Get a string representation of the configuration
     * @return String describing all settings
     */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("RunArguments{\n");
        sb.append("  sourceDir='").append(sourceDir).append("'\n");
        sb.append("  destinationDir='").append(destinationDir).append("'\n");
        sb.append("  flatten=").append(flatten).append("\n");
        sb.append("  saveEdits=").append(saveEdits).append("\n");
        sb.append("  saveComments=").append(saveComments).append("\n");
        sb.append("  exportGps=").append(exportGps).append("\n");
        sb.append("  exportTags=").append(exportTags).append("\n");
        sb.append("  tagsAsFolders=").append(tagsAsFolders).append("\n");
        sb.append("  dryrun=").append(dryrun).append("\n");
        sb.append("}");
        return sb.toString();
    }
}