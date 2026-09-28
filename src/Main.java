import engine.AudioMixerEngine;
import gui.MainWindow;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

public class Main {
    public static void main(String[] args) {
        try {
            UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName());
        } catch (Exception ignored) {}

        try {
            AudioMixerEngine mixer = new AudioMixerEngine();
            mixer.start();

            SwingUtilities.invokeLater(() -> {
                MainWindow window = new MainWindow(mixer);
                window.setVisible(true);
            });
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}