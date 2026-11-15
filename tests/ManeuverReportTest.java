import com.mg4.launcher.svc.ManeuverReport;

/** Run with javac/java; no Android runtime or test framework required. */
public final class ManeuverReportTest {
    public static void main(String[] args) {
        String[] sensitive = {"A2 Utrecht", "Turn right onto Private Street", "Exit right\nRoad: Utrecht",
                "52.0907,5.1214", "Reach destination: My house", "https://example.com/my-location", "", null};
        for (String value : sensitive) {
            if (!"unknown".equals(ManeuverReport.instructionType(value))) throw new AssertionError("Unsafe text accepted");
            String expected = "===== MISSING NAVIGATION ICON =====\nIcon code: 999\nInstruction type: unknown\nDistance meters: 500\n";
            if (!expected.equals(ManeuverReport.body(999, 500, value))) throw new AssertionError("Unexpected report field");
        }
        if (!"exit_right".equals(ManeuverReport.instructionType(" Exit Right "))) throw new AssertionError();
        if (!"roundabout_left".equals(ManeuverReport.instructionType("To left 9 o’clock (At a rotary)"))) throw new AssertionError();
        System.out.println("Maneuver report privacy checks passed");
    }
}
