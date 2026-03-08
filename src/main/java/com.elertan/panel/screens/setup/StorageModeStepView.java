package com.elertan.panel.screens.setup;

import com.elertan.panel.BUPanel;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Color;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;

public class StorageModeStepView extends JPanel implements AutoCloseable {

    private static final int CONTENT_WIDTH = BUPanel.PANEL_WIDTH - 22;

    private final StorageModeStepViewModel viewModel;

    public StorageModeStepView(StorageModeStepViewModel viewModel) {
        this.viewModel = viewModel;

        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBorder(BorderFactory.createEmptyBorder(10, 5, 10, 5));
        setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel titleLabel = new JLabel("How do you want to play?");
        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, 15f));
        titleLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        titleLabel.setMaximumSize(new Dimension(Integer.MAX_VALUE, titleLabel.getPreferredSize().height));
        add(titleLabel);
        add(Box.createVerticalStrut(15));

        JLabel groupLabel = new JLabel("Play with Group");
        groupLabel.setFont(groupLabel.getFont().deriveFont(Font.BOLD, 20f));
        groupLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        add(groupLabel);

        JTextArea groupDescription = createWrappedTextArea(
            "Use a Firebase Realtime Database URL to sync unlocks and rules with friends.",
            null
        );
        add(groupDescription);
        add(Box.createVerticalStrut(8));

        JButton playWithGroupButton = new JButton("Play with Group");
        playWithGroupButton.setAlignmentX(Component.LEFT_ALIGNMENT);
        playWithGroupButton.addActionListener(e -> viewModel.onPlayWithGroupClicked());
        add(playWithGroupButton);
        add(Box.createVerticalStrut(20));

        JLabel soloLabel = new JLabel("Play Solo");
        soloLabel.setFont(soloLabel.getFont().deriveFont(Font.BOLD, 20f));
        soloLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        add(soloLabel);

        JTextArea soloDescription = createWrappedTextArea(
            "Your unlocks and game rules are stored in a simple text file on your computer "
                + "(inside the plugin folder in .runelite). No account or cloud required.",
            null
        );
        add(soloDescription);
        add(Box.createVerticalStrut(3));

        JTextArea soloLimitations = createWrappedTextArea(
            "Limitations: No group sync, no member list, no event broadcasting. "
                + "You can migrate to cloud storage later if you want to play with friends."
            ,
            Color.GRAY
        );
        add(soloLimitations);
        add(Box.createVerticalStrut(10));

        JButton playSoloButton = new JButton("Play Solo");
        playSoloButton.setAlignmentX(Component.LEFT_ALIGNMENT);
        playSoloButton.addActionListener(e -> viewModel.onPlaySoloClicked());
        add(playSoloButton);
    }

    @Override
    public void close() {
        // No bindings to dispose
    }

    private static JTextArea createWrappedTextArea(String text, Color color) {
        JTextArea textArea = new JTextArea(text, 2, 20);
        textArea.setWrapStyleWord(true);
        textArea.setLineWrap(true);
        textArea.setOpaque(false);
        textArea.setEditable(false);
        textArea.setFocusable(false);
        textArea.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));
        textArea.setAlignmentX(Component.LEFT_ALIGNMENT);
        textArea.setMaximumSize(new Dimension(CONTENT_WIDTH, Integer.MAX_VALUE));
        if (color != null) {
            textArea.setForeground(color);
        }
        return textArea;
    }
}
