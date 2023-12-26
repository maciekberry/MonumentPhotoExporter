import java.sql.DriverManager;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Properties;

public class DataConnection {

    private Connection conn = null;

    private String dbfilepath;

    public DataConnection(String dbfilepath) {
        this.dbfilepath = dbfilepath;
    }

    public Connection getConnection() {
        if (conn != null)
            return conn;

        Properties config = new Properties();
        config.setProperty("open_mode", "1");  //1 == readonly

        String url = "jdbc:sqlite:" + dbfilepath;
        try {
            conn = DriverManager.getConnection(url, config);
            return conn;
        } catch (SQLException e) {
            System.out.println(e.getMessage());
        }

        return null;
    }


}
