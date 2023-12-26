import java.nio.file.Files;
import java.nio.file.Path;

public class Main {
    public static void main(String[] args) {

        if (args.length < 2) {
            System.out.println("Missing two manadatory arguments: the directory of monument disk and the directory of export.");
            System.out.println("The monument directory must contain two subfolders: 'monument' and 'Other Files'");
            System.out.println("Be careful: the program does not verify the amount of space available on the export storage");
            System.out.println("");
            System.out.println("Example usage: java Main /media/user/foto /home/user/Pictures/");
            System.out.println("Third argument is optional: --dry-run (it will create directories and empty files)");
            return;
        }

        RunArguments a = new RunArguments();
        a.sourceDir = args[0];
        a.destinationDir = args[1];
        if ((args.length == 3) && ("--dry-run".equals(args[2])) ){
            a.dryrun = true;
        }


        //preliminary sanity check
        if (!Files.exists(Path.of(a.sourceDir + "/monument/.userdata/m.sqlite3"))) {
            System.out.println("The monument directory '" + a.sourceDir + "' does not look like monument directory. File monument/.userdata/m.sqlite3 is missing");
            return;
        }

        if (!Files.exists(Path.of(a.destinationDir))) {
            System.out.println("The export directory '" + a.destinationDir + "' does not exists. I think.");
            return;
        }

        if (!Files.isDirectory(Path.of(a.destinationDir))) {
            System.out.println("The export directory '" + a.destinationDir + "' is not a directory. Cannot continue here.");
            return;
        }

        if (Path.of(a.destinationDir).toFile().listFiles().length > 0) {
            System.out.println("The export directory '" + a.destinationDir + "' is not empty. I'd better stop here.");
            return;
        }

        if (a.dryrun) {
            System.out.println("DRY RUN EXPORT. NOT COPYING ANY ACTUAL FILES");
        }

        System.out.println("Exporting from monument directory: '" + a.sourceDir + "'");
        System.out.println("Exporting to: '" + a.destinationDir + "'");

        Exporter e = new Exporter(a);
        e.init();
        e.export();
    }
}
