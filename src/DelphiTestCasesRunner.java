import java.io.*;
import java.util.*;

public class DelphiTestCasesRunner {
    public static void main(String[] args) throws IOException {
        boolean debug = args.length == 1 && Boolean.parseBoolean(args[0]);

        String testFolder = "TestCases";
        File folder = new File(testFolder);
        List<File> pasFiles = new ArrayList<>(Arrays.stream(Objects.requireNonNull(folder.listFiles((dir, name) -> name.endsWith(".pas")))).toList());

        if (pasFiles.isEmpty()) {
            System.out.println("No .pas files found in TestCases folder.");
            return;
        }

        // Redirect output to output.pas
        PrintStream originalOut = System.out;
        PrintStream fileOut = new PrintStream(new FileOutputStream("output.txt"));
        System.setOut(fileOut);

        Collections.sort(pasFiles);

        for (File file : pasFiles) {
            System.out.println("\nRunning test: " + file.getName());
            String[] interpreterArgs = { file.getName(), String.valueOf(debug) };
            DelphiInterpreter.main(interpreterArgs);
        }

        System.setOut(originalOut);
        System.out.println("All test outputs written to output.pas, check the root folder");
    }
}
