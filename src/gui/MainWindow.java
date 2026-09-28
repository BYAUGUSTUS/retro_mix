package gui;

import engine.AudioMixerEngine;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.swing.*;
import javax.swing.plaf.basic.BasicScrollBarUI;
import javax.swing.plaf.basic.BasicSliderUI;

public class MainWindow extends JFrame {

    private final AudioMixerEngine mixer;
    private double vinylAngle = 0.0;

    // Tonearm physics & angle
    private double currentArmAngle = 0.0;
    private final double PARKED_ARM_ANGLE = 0.0;
    private final double PLAYING_ARM_ANGLE = 0.38;

    private JSlider volumeSlider;
    private JButton muteButton;
    private boolean isMuted = false;
    private float preMuteVolume = 0.85f;

    // Direct Vinyl Cursor Drag & Physics
    private boolean isMouseDraggingRecord = false;
    private double lastMouseAngle = 0.0;
    private double currentVinylRadius = 150.0;
    private int vinylCenterX = 0;
    private int vinylCenterY = 0;

    // Native Typography
    private Font titleFont;
    private Font trackFont;
    private Font uiButtonFont;
    private Font playlistFont;

    // Floating Glass Playlist
    private JLayeredPane layeredPane;
    private VinylCanvas canvas;
    private JPanel playlistOverlay;
    private JPanel transportBar;
    private JPanel volumePanel;
    private DefaultListModel<String> playlistModel;
    private JList<String> playlistView;
    private boolean isPlaylistVisible = false;

    public MainWindow(AudioMixerEngine mixer) {
        super("Retro Mix");
        this.mixer = mixer;

        try {
            java.io.InputStream stream = getClass().getResourceAsStream("/assets/icon.png");
            if (stream == null) {
                // Fallback to local file path during IDE/dev execution
                File fallback = new File("assets/icon.png");
                if (fallback.exists()) {
                    stream = new java.io.FileInputStream(fallback);
                }
            }
            if (stream != null) {
                Image icon = javax.imageio.ImageIO.read(stream);
                setIconImage(icon);
            }
        } catch (Exception e) {
            System.err.println("Could not load window icon: " + e.getMessage());
        }

        initTypography();

        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(960, 650);
        setMinimumSize(new Dimension(880, 580));
        setLocationRelativeTo(null);

        layeredPane = new JLayeredPane();
        setContentPane(layeredPane);

        canvas = new VinylCanvas();
        layeredPane.add(canvas, JLayeredPane.DEFAULT_LAYER);

        volumePanel = createRightVolumePanel();
        layeredPane.add(volumePanel, JLayeredPane.PALETTE_LAYER);

        transportBar = createTransportBar();
        layeredPane.add(transportBar, JLayeredPane.PALETTE_LAYER);

        initFloatingPlaylistOverlay();
        layeredPane.add(playlistOverlay, JLayeredPane.MODAL_LAYER);

        addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                relayoutComponents();
            }
        });

        setupVinylDirectMouseScratch();

        // 60 FPS Visual & Animation Loop
        Timer timer = new Timer(16, e -> {
            boolean playing = mixer.isPlaying();
            float rate = mixer.getActivePlaybackRate();

            // Vinyl visual rotation tracks audio playback velocity
            if (playing && !isMouseDraggingRecord) {
                vinylAngle += (0.038 * rate);
                if (vinylAngle >= Math.PI * 2) vinylAngle -= Math.PI * 2;
                if (vinylAngle < 0) vinylAngle += Math.PI * 2;
            }

            double targetArm = playing ? PLAYING_ARM_ANGLE : PARKED_ARM_ANGLE;
            if (Math.abs(currentArmAngle - targetArm) > 0.001) {
                currentArmAngle += (targetArm - currentArmAngle) * 0.065;
            }

            canvas.repaint();
        });
        timer.start();
    }

    private void setupVinylDirectMouseScratch() {
        MouseAdapter scratchAdapter = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                double dist = Point.distance(e.getX(), e.getY(), vinylCenterX, vinylCenterY);
                if (dist <= currentVinylRadius) {
                    isMouseDraggingRecord = true;
                    lastMouseAngle = Math.atan2(e.getY() - vinylCenterY, e.getX() - vinylCenterX);
                    mixer.setManualScratchRate(0.0f);
                }
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                if (!isMouseDraggingRecord) return;

                double currentAngle = Math.atan2(e.getY() - vinylCenterY, e.getX() - vinylCenterX);
                double dTheta = currentAngle - lastMouseAngle;

                // Handle angular boundary wrapping (-PI to +PI)
                if (dTheta > Math.PI) dTheta -= 2 * Math.PI;
                if (dTheta < -Math.PI) dTheta += 2 * Math.PI;

                vinylAngle += dTheta;
                lastMouseAngle = currentAngle;

                // Map hand rotational velocity into playback rate
                float targetRate = (float) (dTheta * 18.0);
                targetRate = Math.max(-5.0f, Math.min(5.0f, targetRate));
                mixer.setManualScratchRate(targetRate);
                canvas.repaint();
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (isMouseDraggingRecord) {
                    isMouseDraggingRecord = false;
                    mixer.releaseScratch();
                }
            }
        };

        canvas.addMouseListener(scratchAdapter);
        canvas.addMouseMotionListener(scratchAdapter);
    }

    private void initTypography() {
        titleFont = resolveFont("Baskerville Old Face", "Georgia", Font.BOLD, 14);
        trackFont = resolveFont("Baskerville Old Face", "Georgia", Font.ITALIC, 20);
        uiButtonFont = resolveFont("Century Gothic", "Segoe UI", Font.BOLD, 11);
        playlistFont = resolveFont("Century Gothic", "Segoe UI", Font.PLAIN, 12);
    }

    private Font resolveFont(String preferred, String fallback, int style, int size) {
        Font font = new Font(preferred, style, size);
        if (font.getFamily().equalsIgnoreCase("Dialog")) {
            return new Font(fallback, style, size);
        }
        return font;
    }

    private void relayoutComponents() {
        int w = getContentPane().getWidth();
        int h = getContentPane().getHeight();

        canvas.setBounds(0, 0, w, h);

        int barHeight = 55;
        transportBar.setBounds(0, h - barHeight - 12, w - 80, barHeight);

        int volWidth = 55;
        volumePanel.setBounds(w - volWidth - 14, 50, volWidth, h - 140);

        playlistOverlay.setBounds(24, 75, 280, Math.max(220, h - 170));
    }

    private void initFloatingPlaylistOverlay() {
        playlistOverlay = new JPanel(new BorderLayout(0, 10)) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(new Color(16, 16, 20, 240));
                g2.fill(new RoundRectangle2D.Float(0, 0, getWidth(), getHeight(), 16, 16));
                g2.setColor(new Color(60, 60, 70));
                g2.setStroke(new BasicStroke(1.2f));
                g2.draw(new RoundRectangle2D.Float(0, 0, getWidth() - 1, getHeight() - 1, 16, 16));
                g2.dispose();
            }
        };
        playlistOverlay.setOpaque(false);
        playlistOverlay.setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14));
        playlistOverlay.setVisible(false);

        JLabel header = new JLabel("C R A T E   Q U E U E", SwingConstants.LEFT);
        header.setFont(titleFont.deriveFont(Font.BOLD, 12f));
        header.setForeground(new Color(255, 180, 50));
        playlistOverlay.add(header, BorderLayout.NORTH);

        playlistModel = new DefaultListModel<>();
        playlistView = new JList<>(playlistModel);
        playlistView.setOpaque(false);
        playlistView.setBackground(new Color(0, 0, 0, 0));
        playlistView.setForeground(new Color(215, 215, 225));
        playlistView.setFont(playlistFont);
        playlistView.setSelectionBackground(new Color(215, 60, 35));
        playlistView.setSelectionForeground(Color.WHITE);
        playlistView.setFixedCellHeight(26);

        playlistView.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    int idx = playlistView.getSelectedIndex();
                    if (idx != -1) {
                        mixer.playTrackAtIndex(idx);
                        canvas.repaint();
                    }
                }
            }
        });

        JScrollPane scroll = new JScrollPane(playlistView);
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getVerticalScrollBar().setUI(new CustomScrollBarUI());
        scroll.getVerticalScrollBar().setPreferredSize(new Dimension(6, 0));

        playlistOverlay.add(scroll, BorderLayout.CENTER);
    }

    private void updatePlaylistModel() {
        SwingUtilities.invokeLater(() -> {
            playlistModel.clear();
            for (File f : mixer.getPlaylist()) {
                playlistModel.addElement(f.getName().replaceFirst("[.][^.]+$", ""));
            }
            if (mixer.getPlaylistIndex() != -1) {
                playlistView.setSelectedIndex(mixer.getPlaylistIndex());
            }
        });
    }

    private JPanel createRightVolumePanel() {
        JPanel panel = new JPanel();
        panel.setOpaque(false);
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));

        muteButton = new JButton("VOL");
        muteButton.setFont(uiButtonFont.deriveFont(Font.BOLD, 10f));
        muteButton.setBackground(new Color(32, 32, 35));
        muteButton.setForeground(new Color(255, 180, 50));
        muteButton.setFocusPainted(false);
        muteButton.setBorder(BorderFactory.createRaisedBevelBorder());
        muteButton.setAlignmentX(Component.CENTER_ALIGNMENT);
        muteButton.setPreferredSize(new Dimension(36, 24));
        muteButton.setMaximumSize(new Dimension(36, 24));

        muteButton.addActionListener(e -> {
            if (!isMuted) {
                preMuteVolume = mixer.getMasterVolume();
                mixer.setMasterVolume(0.0f);
                volumeSlider.setValue(0);
                muteButton.setForeground(new Color(225, 45, 45));
                isMuted = true;
            } else {
                mixer.setMasterVolume(preMuteVolume);
                volumeSlider.setValue((int) (preMuteVolume * 100));
                muteButton.setForeground(new Color(255, 180, 50));
                isMuted = false;
            }
        });

        volumeSlider = new JSlider(JSlider.VERTICAL, 0, 100, 85);
        volumeSlider.setOpaque(false);
        volumeSlider.setFocusable(false);
        volumeSlider.setAlignmentX(Component.CENTER_ALIGNMENT);
        volumeSlider.setUI(new RetroVerticalSliderUI(volumeSlider));

        volumeSlider.addChangeListener(e -> {
            float val = volumeSlider.getValue() / 100.0f;
            mixer.setMasterVolume(val);
            if (val > 0 && isMuted) {
                isMuted = false;
                muteButton.setForeground(new Color(255, 180, 50));
            }
        });

        panel.add(muteButton);
        panel.add(Box.createVerticalStrut(10));
        panel.add(volumeSlider);
        return panel;
    }

    private JPanel createTransportBar() {
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.CENTER, 12, 8));
        bar.setOpaque(false);

        JButton btnFolder = createRetroButton("DIR FOLDER");
        JButton btnPlaylist = createRetroButton("PLAYLIST");
        JButton btnRewind = createRetroButton("<< REW");
        JButton btnPlay = createRetroButton("PLAY");
        JButton btnPause = createRetroButton("PAUSE");
        JButton btnForward = createRetroButton("FWD >>");
        JButton btnNext = createRetroButton("NEXT");

        btnFolder.addActionListener(e -> {
            JFileChooser chooser = new JFileChooser();
            chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            chooser.setDialogTitle("Select Your Music Download Folder");
            if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                mixer.setWatchedFolder(chooser.getSelectedFile(), this::updatePlaylistModel);
                canvas.repaint();
            }
        });

        btnPlaylist.addActionListener(e -> {
            isPlaylistVisible = !isPlaylistVisible;
            playlistOverlay.setVisible(isPlaylistVisible);
            if (isPlaylistVisible) updatePlaylistModel();
            canvas.repaint();
        });

        btnRewind.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) { mixer.startRewindScratch(); }
            @Override
            public void mouseReleased(MouseEvent e) { mixer.releaseScratch(); }
        });

        btnForward.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) { mixer.startForwardScratch(); }
            @Override
            public void mouseReleased(MouseEvent e) { mixer.releaseScratch(); }
        });

        btnPlay.addActionListener(e -> mixer.play());
        btnPause.addActionListener(e -> mixer.pause());
        btnNext.addActionListener(e -> {
            mixer.playNextTrack();
            updatePlaylistModel();
            canvas.repaint();
        });

        bar.add(btnFolder);
        bar.add(btnPlaylist);
        bar.add(btnRewind);
        bar.add(btnPlay);
        bar.add(btnPause);
        bar.add(btnForward);
        bar.add(btnNext);

        return bar;
    }

    private JButton createRetroButton(String label) {
        JButton btn = new JButton(label);
        btn.setFont(uiButtonFont);
        btn.setBackground(new Color(26, 26, 30));
        btn.setForeground(new Color(220, 220, 228));
        btn.setFocusPainted(false);
        btn.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createRaisedBevelBorder(),
                BorderFactory.createEmptyBorder(6, 14, 6, 14)
        ));
        return btn;
    }

    private class VinylCanvas extends JPanel {
        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            int width = getWidth();
            int height = getHeight();

            g2.setPaint(new GradientPaint(0, 0, new Color(17, 17, 20), 0, height, new Color(11, 11, 13)));
            g2.fillRect(0, 0, width, height);

            g2.setFont(titleFont);
            g2.setColor(new Color(255, 180, 50));
            g2.drawString("R E T R O   M I X", 36, 38);

            String trackName = mixer.getCurrentTrackName();
            g2.setFont(trackFont);
            g2.setColor(new Color(230, 230, 235));
            g2.drawString(trackName, 36, 64);

            int cx = (width - 60) / 2;
            int cy = (height - 60) / 2;
            int radius = Math.min((width - 240) / 2, (height - 180) / 2);
            radius = Math.max(120, radius);

            // Export coordinates for mouse scratching hit-testing
            vinylCenterX = cx;
            vinylCenterY = cy;
            currentVinylRadius = radius;

            // Platter rim
            g2.setColor(new Color(26, 26, 30));
            g2.fillOval(cx - radius - 8, cy - radius - 8, (radius + 8) * 2, (radius + 8) * 2);
            g2.setColor(new Color(50, 50, 55));
            g2.setStroke(new BasicStroke(1.5f));
            g2.drawOval(cx - radius - 8, cy - radius - 8, (radius + 8) * 2, (radius + 8) * 2);

            // Vinyl body
            g2.setColor(new Color(10, 10, 12));
            g2.fillOval(cx - radius, cy - radius, radius * 2, radius * 2);

            // Micro-grooves
            g2.setColor(new Color(42, 42, 50, 55));
            for (int r = radius - 8; r > radius / 2; r -= 5) {
                g2.drawOval(cx - r, cy - r, r * 2, r * 2);
            }

            // Center artwork label
            Graphics2D vg = (Graphics2D) g2.create();
            vg.translate(cx, cy);
            vg.rotate(vinylAngle);

            int labelRadius = (int) (radius * 0.44);
            BufferedImage art = mixer.getCurrentAlbumArt();

            if (art != null) {
                Shape circleMask = new Ellipse2D.Float(-labelRadius, -labelRadius, labelRadius * 2, labelRadius * 2);
                vg.setClip(circleMask);
                vg.drawImage(art, -labelRadius, -labelRadius, labelRadius * 2, labelRadius * 2, null);
                vg.setClip(null);
            } else {
                vg.setColor(new Color(210, 60, 35));
                vg.fillOval(-labelRadius, -labelRadius, labelRadius * 2, labelRadius * 2);
                vg.setColor(new Color(242, 237, 225));
                vg.fillOval(-labelRadius + 8, -labelRadius + 8, (labelRadius - 8) * 2, (labelRadius - 8) * 2);

                vg.setColor(new Color(20, 20, 22));
                vg.setFont(titleFont.deriveFont(Font.BOLD, 10f));
                vg.drawString("33 1/3 RPM", -28, -6);
                vg.drawString("RETRO MIX", -28, 10);
            }

            vg.setColor(new Color(200, 200, 205));
            vg.fillOval(-8, -8, 16, 16);
            vg.setColor(new Color(10, 10, 12));
            vg.fillOval(-4, -4, 8, 8);
            vg.dispose();

            // Specular lighting reflection across grooves
            GradientPaint sheen = new GradientPaint(
                    cx - radius, cy - radius, new Color(255, 255, 255, 26),
                    cx + radius, cy + radius, new Color(255, 255, 255, 0)
            );
            g2.setPaint(sheen);
            g2.fillOval(cx - radius, cy - radius, radius * 2, radius * 2);

            drawRealisticTonearm(g2, cx, cy, radius);
            g2.dispose();
        }

        private void drawRealisticTonearm(Graphics2D g2, int cx, int cy, int radius) {
            int baseX = cx + radius + 38;
            int baseY = cy - (radius / 2);

            Graphics2D tg = (Graphics2D) g2.create();
            tg.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            tg.setPaint(new GradientPaint(baseX - 30, baseY - 30, new Color(55, 55, 60), baseX + 30, baseY + 30, new Color(20, 20, 24)));
            tg.fillOval(baseX - 28, baseY - 28, 56, 56);
            tg.setColor(new Color(80, 80, 90));
            tg.setStroke(new BasicStroke(1.5f));
            tg.drawOval(baseX - 28, baseY - 28, 56, 56);

            tg.setColor(new Color(35, 35, 40));
            tg.fillRoundRect(baseX - 4, baseY + 26, 8, 24, 4, 4);
            tg.setColor(new Color(70, 70, 78));
            tg.drawRoundRect(baseX - 4, baseY + 26, 8, 24, 4, 4);

            tg.translate(baseX, baseY);
            tg.rotate(currentArmAngle);

            int cwW = 26;
            int cwH = 34;
            int cwY = -48;
            GradientPaint cwGrad = new GradientPaint(-cwW / 2f, 0, new Color(130, 130, 138), cwW / 2f, 0, new Color(50, 50, 55));
            tg.setPaint(cwGrad);
            tg.fillRoundRect(-cwW / 2, cwY, cwW, cwH, 6, 6);

            tg.setColor(new Color(255, 180, 50));
            tg.fillRect(-cwW / 2, cwY + 14, cwW, 2);

            tg.setPaint(new GradientPaint(-14, -14, new Color(210, 210, 220), 14, 14, new Color(70, 70, 75)));
            tg.fillOval(-14, -14, 28, 28);
            tg.setColor(new Color(30, 30, 35));
            tg.fillOval(-6, -6, 12, 12);

            int wandLen = (int) (radius * 1.10);

            Path2D.Float armPath = new Path2D.Float();
            armPath.moveTo(0, 8);
            armPath.curveTo(-14, wandLen * 0.35, 18, wandLen * 0.70, 2, wandLen);

            tg.setColor(new Color(0, 0, 0, 100));
            tg.setStroke(new BasicStroke(6.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            tg.translate(2, 3);
            tg.draw(armPath);
            tg.translate(-2, -3);

            tg.setColor(new Color(60, 60, 68));
            tg.setStroke(new BasicStroke(5.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            tg.draw(armPath);

            tg.setColor(new Color(235, 235, 245));
            tg.setStroke(new BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            tg.draw(armPath);

            tg.translate(2, wandLen);
            tg.rotate(-0.40);

            tg.setColor(new Color(180, 180, 190));
            tg.fillRoundRect(-3, -6, 6, 8, 2, 2);

            tg.setPaint(new GradientPaint(-7, 0, new Color(45, 45, 50), 7, 0, new Color(18, 18, 22)));
            tg.fillRoundRect(-7, 2, 14, 30, 5, 5);
            tg.setColor(new Color(75, 75, 82));
            tg.setStroke(new BasicStroke(1.0f));
            tg.drawRoundRect(-7, 2, 14, 30, 5, 5);

            tg.setColor(new Color(190, 190, 200));
            tg.setStroke(new BasicStroke(1.8f));
            tg.drawLine(7, 10, 16, 8);

            tg.setColor(new Color(225, 50, 30));
            tg.fillRect(-3, 26, 6, 5);

            tg.dispose();
        }
    }

    private static class RetroVerticalSliderUI extends BasicSliderUI {
        public RetroVerticalSliderUI(JSlider b) { super(b); }

        @Override
        public void paintTrack(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int trackX = trackRect.x + (trackRect.width / 2) - 3;
            g2.setColor(new Color(10, 10, 12));
            g2.fillRoundRect(trackX, trackRect.y, 6, trackRect.height, 4, 4);
            g2.dispose();
        }

        @Override
        public void paintThumb(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            int x = thumbRect.x;
            int y = thumbRect.y;
            int w = thumbRect.width;
            int h = thumbRect.height;

            GradientPaint gp = new GradientPaint(x, y, new Color(85, 85, 90), x, y + h, new Color(38, 38, 42));
            g2.setPaint(gp);
            g2.fillRoundRect(x, y, w, h, 6, 6);

            g2.setColor(new Color(255, 180, 50));
            g2.drawLine(x + 2, y + (h / 2), x + w - 3, y + (h / 2));

            g2.setColor(new Color(125, 125, 130));
            g2.drawRoundRect(x, y, w, h, 6, 6);
            g2.dispose();
        }

        @Override
        protected Dimension getThumbSize() { return new Dimension(22, 16); }
    }

    private static class CustomScrollBarUI extends BasicScrollBarUI {
        @Override
        protected void configureScrollBarColors() {
            this.thumbColor = new Color(65, 65, 75);
            this.trackColor = new Color(0, 0, 0, 0);
        }

        @Override
        protected JButton createDecreaseButton(int orientation) { return createZeroButton(); }
        @Override
        protected JButton createIncreaseButton(int orientation) { return createZeroButton(); }

        private JButton createZeroButton() {
            JButton jbutton = new JButton();
            jbutton.setPreferredSize(new Dimension(0, 0));
            return jbutton;
        }
    }
}