import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.*;
import java.util.HashMap;
import java.util.Map;

public class Exporter {

    //public static final String EXPORT_DIR = "OUTPUT/";

    class DestinationDescription {
        public boolean owner_changed = false;
        public int new_owner = 0;
        public String dest = "";
    }

    private  DataConnection d;

    private Map<Integer, String> monumentUsers = new HashMap<Integer,String>();

    private RunArguments args;

    public Exporter(RunArguments args) {
        this.args = args;
    }

    private boolean createUserMap() {
        monumentUsers.put(0, "unknown_user");
        Connection conn = d.getConnection();

        String sql = "SELECT id, name, status FROM user";
        try (Statement stmt  = conn.createStatement();
             ResultSet rs    = stmt.executeQuery(sql)){
            while (rs.next()) {
                String name;
                if (rs.getString("status").equals("deleted"))
                    name = rs.getString("name") + "_" + rs.getString("id") + "_deleted";
                else
                    name = rs.getString("name") + "_" + rs.getString("id");

                monumentUsers.put(rs.getInt("id"), name);
            }


        } catch (SQLException e) {
            e.printStackTrace();
            return false;
        }
        return true;
    }

    private void copyContent(String from, String name, String dest, String checksum) throws IOException {

        Files.createDirectories(Paths.get(dest));

        Path destPath = Path.of(dest + "/" + name);

        if (Files.exists(destPath)) {
            //special case of identical file names in Monument database
            String newName = name;
            int lastDot = name.lastIndexOf('.');

            if (lastDot <= 0)
                newName = name + "-" + checksum;
            else
                newName = name.substring(0, lastDot) + "-" + checksum + name.substring(lastDot);

            System.out.println("WARNING: " + destPath.toString() + " -> the file existed already, changing to " + newName);
            destPath = Path.of(dest + "/" + newName);

        }
        if (args.dryrun) {
            Files.createFile(destPath);
        }
        else {
            try {
                Files.copy(Path.of(from), destPath);
            }
            catch (java.nio.file.NoSuchFileException e) {
                System.out.println("ERROR: " + from + " -> FILE DOES NOT EXIST IN MONUMENT DISK. WE CONTINUE ANYWAY");
            }
        }

    }

    private DestinationDescription getDest(int content_id, int content_owner) throws SQLException {

        DestinationDescription result = new DestinationDescription();

        Connection conn = d.getConnection();
        String sql = "SELECT ac.album_id, ac.content_id, afa.folder_id as folder_id, a.name, a.user_id as album_owner_id " +
                "FROM albumcontent as ac " +
                "join album as a on ac.album_id = a.id " +
                "left join albumfolderalbum as afa on ac.album_id = afa.album_id " +
                "left join albumfolder as af on (afa.folder_id = af.id and original_folder_id is null) " +
                "where content_id = ?";

        PreparedStatement pstmt  = conn.prepareStatement(sql);

        pstmt.setInt(1,content_id);

        ResultSet rs   = pstmt.executeQuery();

        boolean is_empty =  !(rs.next());
        int folder_id = rs.getInt("folder_id");
        String album_name = rs.getString("name");
        int album_owner_id = rs.getInt("album_owner_id");

        rs.close();
        pstmt.close();


        if (is_empty) {

            sql = "SELECT taken FROM content WHERE id = ?";
            pstmt = conn.prepareStatement(sql);
            pstmt.setInt(1,content_id);

            ResultSet rs = pstmt.executeQuery();

            boolean.is_empty = !(rs.next());
            String datetime = rs.getString("taken");
            rs.close();
            pstmt.close();

            String year = datetime.substring(0,3);
            String month = datetime.substring(5,6);
            String day = datetime.substring(8,9);
            
            //special case: the content not in an album
            result.dest = monumentUsers.get(content_owner) + "/PHOTOS_WITHOUT_ALBUM/" + year + "/" + month + "/" + day + "/";
            result.owner_changed = false;
            return result;
        }

        if (folder_id == 0) {
            //another special case: content in album, but album not in folder
            result.dest = monumentUsers.get(album_owner_id) + "/" + album_name;
            result.owner_changed = (album_owner_id != content_owner);
            result.new_owner = album_owner_id;
            return result;
        }


        sql = """
                WITH RECURSIVE cte_AlbumFolder (id, name, parent_id, user_id) AS (
                    SELECT e.id, e.name, e.parent_id, e.user_id
                    FROM AlbumFolder e
                    WHERE e.id = ?

                    UNION ALL

                    SELECT e.id, e.name, e.parent_id, e.user_id
                    FROM AlbumFolder e
                             JOIN cte_AlbumFolder c ON c.parent_id = e.id
                )

                SELECT * FROM cte_AlbumFolder;""";

        PreparedStatement pstmt2  = conn.prepareStatement(sql);

        pstmt2.setInt(1,folder_id);

        ResultSet rs2   = pstmt2.executeQuery();

        StringBuilder path = new StringBuilder();
        int user_id = 0;
        while (rs2.next()) {
            //System.out.println(rs2.getString("name"));
            path.insert(0,"/");
            path.insert(0, rs2.getString("name"));
            user_id = rs2.getInt("user_id");
        }
        rs2.close();
        pstmt2.close();

        path.append(album_name);
        path.insert(0,"/").insert(0, monumentUsers.get(user_id));

        result.owner_changed = (user_id != content_owner);
        result.new_owner = user_id;
        result.dest = path.toString();

        return result;
    }



    public void export() {
        Connection conn = d.getConnection();

        if (!createUserMap()) return;

        int counter = 0;

        String monumentRoot = args.sourceDir + "/";

        String sql = "SELECT id, user_id, type, path, filename, checksum FROM Content where deleted_at is null";//where id = 30671 / 32414

        try (Statement stmt  = conn.createStatement();
             ResultSet rs    = stmt.executeQuery(sql)){

            // for every content (file)
            while (rs.next()) {
                DestinationDescription dd = getDest(rs.getInt("id"), rs.getInt("user_id"));

                String destination = args.destinationDir + "/" + dd.dest;

                if (dd.owner_changed) {
                    String old_owner = monumentUsers.get(rs.getInt("user_id"));
                    String new_owner = monumentUsers.get(dd.new_owner);
                    System.out.println("INFO: " + rs.getInt("id") + "\t" + rs.getString("path") +
                            "\t => \t" + destination + " - OWNER CHANGED FROM " +
                            old_owner + " TO " + new_owner);
                }
                else
                    System.out.println("DEBUG: " + rs.getInt("id") + "\t" + rs.getString("path") + "\t => \t" + destination);

                String from = monumentRoot + rs.getString("path");

                String fileName = rs.getString("filename");
                String checksumUnique = rs.getString("checksum");
                copyContent(from, fileName , destination, checksumUnique);

                counter++;
            }

            if (args.dryrun)
                System.out.println("INFO: Successfully simulated the export of " + counter + " files" );
            else
                System.out.println("INFO: Successfully exported " + counter + " files" );

        } catch (SQLException | IOException e) {
           // System.out.println(e.getMessage());
            e.printStackTrace();
        }

    }

    public void init() {

        String dbfilepath = args.sourceDir + "/monument/.userdata/m.sqlite3";
        System.out.println("INFO: Database file is " + dbfilepath);

        d = new DataConnection(dbfilepath);
        d.getConnection();
    }

}
