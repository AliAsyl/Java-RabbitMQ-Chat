import com.rabbitmq.client.*;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeoutException;

public class ChatClient {
    private static final String REQUEST_USERLIST = "REQUEST_USERLIST:";
    private static final String ANNOUNCE_USER = "ANNOUNCE_USER:";
    private static final String JOIN = "JOIN:";
    private static final String LEAVE = "LEAVE:";
    private static final String NEWROOM = "NEWROOM:";
    private static final String REQUEST_ROOMLIST = "REQUEST_ROOMLIST:";

    private static final String RABBIT_HOST = "localhost";

    private Connection connection;
    private Channel conChannel;

    private String userName;
    private String userRoom;

    private final String chatExchange;
    private final String presenceExchange;
    private static final String privateExchange = "private";

    private String chatQueueName;
    private String presenceQueueName;
    private String privateQueueName;

    private JFrame frame;
    private JTextArea chat;
    private DefaultListModel<String> usersModel;
    private JList<String> usersList;
    private JTextField inputText;
    private JButton sendButton;

    private final Set<String> onlineUsers = new HashSet<>();

    private static final String ROOMS_EXCHANGE = "rooms";
    private String roomsQueueName;
    private final Set<String> existingRooms = new TreeSet<>();
    private final Set<String> pendingRoomRequesters = new HashSet<>();
    private boolean hasChosenRoom = false;

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try {
                new ChatClient();
            } catch (Exception ex) {
                ex.printStackTrace();
                System.exit(1);
            }
        });
    }

    public ChatClient() throws IOException, TimeoutException, InterruptedException {
        this.userName = JOptionPane.showInputDialog(
                null,
                "Podaj swoją nazwę (userName):",
                "Nickname",
                JOptionPane.QUESTION_MESSAGE
        );
        if (userName == null || userName.trim().isEmpty()) {
            System.exit(0);
        }
        this.userName = userName.trim();

        initRoomsMQ();

        sendRoomsMessage(REQUEST_ROOMLIST + this.userName);

        Thread.sleep(500);

        this.userRoom = promptForRoom();
        if (userRoom == null || userRoom.trim().isEmpty()) {
            System.exit(0);
        }
        this.userRoom = userRoom.trim();
        hasChosenRoom = true;

        sendRoomsMessage(NEWROOM + this.userRoom);
        if (!pendingRoomRequesters.isEmpty()) {
            sendRoomsMessage(NEWROOM + this.userRoom);
            pendingRoomRequesters.clear();
        }

        this.chatExchange     = "chat_"     + this.userRoom;
        this.presenceExchange = "presence_" + this.userRoom;

        initChatAndPresenceMQ();

        sendPresence(REQUEST_USERLIST + this.userName);
        sendPresence(JOIN            + this.userName);

        initGUI();
    }

    private void initRoomsMQ() throws IOException, TimeoutException {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(RABBIT_HOST);

        connection = factory.newConnection();
        conChannel = connection.createChannel();

        conChannel.exchangeDeclare(ROOMS_EXCHANGE, BuiltinExchangeType.FANOUT, false, false, null);

        AMQP.Queue.DeclareOk roomsQ = conChannel.queueDeclare("", false, true, true, null);
        roomsQueueName = roomsQ.getQueue();
        conChannel.queueBind(roomsQueueName, ROOMS_EXCHANGE, "");

        conChannel.basicConsume(roomsQueueName, true, getRoomsDeliverCallback(), tag -> { });
    }

    private DeliverCallback getRoomsDeliverCallback() {
        return (consumerTag, delivery) -> {
            String msg = new String(delivery.getBody(), StandardCharsets.UTF_8);

            if (msg.startsWith(REQUEST_ROOMLIST)) {
                String requester = msg.substring(REQUEST_ROOMLIST.length());
                if (hasChosenRoom) {
                    sendRoomsMessage(NEWROOM + this.userRoom);
                } else {
                    pendingRoomRequesters.add(requester);
                }
                return;
            }

            if (msg.startsWith(NEWROOM)) {
                String roomName = msg.substring(NEWROOM.length());
                synchronized (existingRooms) {
                    if (!existingRooms.contains(roomName)) {
                        existingRooms.add(roomName);
                    }
                }
            }
        };
    }

    private void sendRoomsMessage(String text) {
        try {
            conChannel.basicPublish(
                    ROOMS_EXCHANGE,
                    "",
                    null,
                    text.getBytes(StandardCharsets.UTF_8)
            );
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private String promptForRoom() {
        JPanel panel = new JPanel(new BorderLayout(5, 5));
        panel.setBorder(new EmptyBorder(10, 10, 10, 10));
        panel.add(new JLabel("Wybierz pokój lub wpisz nowy:"), BorderLayout.NORTH);

        JComboBox<String> combo;
        synchronized (existingRooms) {
            combo = new JComboBox<>(existingRooms.toArray(new String[0]));
        }
        combo.setEditable(true);
        combo.setPreferredSize(new Dimension(200, 25));
        panel.add(combo, BorderLayout.CENTER);

        int result = JOptionPane.showConfirmDialog(
                null,
                panel,
                "Room",
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE
        );
        if (result == JOptionPane.OK_OPTION) {
            Object selected = combo.getEditor().getItem();
            return (selected == null ? "" : selected.toString());
        } else {
            return null;
        }
    }

    private void initChatAndPresenceMQ() throws IOException, TimeoutException {
        conChannel.exchangeDeclare(chatExchange, BuiltinExchangeType.FANOUT, false, false, null);
        AMQP.Queue.DeclareOk chatQ = conChannel.queueDeclare("", false, true, true, null);
        chatQueueName = chatQ.getQueue();
        conChannel.queueBind(chatQueueName, chatExchange, "");
        conChannel.basicConsume(chatQueueName, true, getChatDeliverCallback(), tag -> { });

        conChannel.exchangeDeclare(presenceExchange, BuiltinExchangeType.FANOUT, false, false, null);
        AMQP.Queue.DeclareOk presQ = conChannel.queueDeclare("", false, true, true, null);
        presenceQueueName = presQ.getQueue();
        conChannel.queueBind(presenceQueueName, presenceExchange, "");
        conChannel.basicConsume(presenceQueueName, true, getPresenceDeliverCallback(), tag -> { });

        conChannel.exchangeDeclare(privateExchange, BuiltinExchangeType.DIRECT, false, false, null);
        privateQueueName = "private_" + this.userName;
        conChannel.queueDeclare(privateQueueName, false, true, true, null);
        conChannel.queueBind(privateQueueName, privateExchange, this.userName);
        conChannel.basicConsume(privateQueueName, true, getPrivateDeliverCallback(), tag -> { });
    }

    private DeliverCallback getChatDeliverCallback() {
        return (consumerTag, delivery) -> {
            String msg = new String(delivery.getBody(), StandardCharsets.UTF_8);
            appendToChatArea(msg);
        };
    }

    private DeliverCallback getPrivateDeliverCallback() {
        return (consumerTag, delivery) -> {
            String msg = new String(delivery.getBody(), StandardCharsets.UTF_8);
            appendToChatArea("[PRYWATNIE] " + msg);
        };
    }

    private DeliverCallback getPresenceDeliverCallback() {
        return (consumerTag, delivery) -> {
            String msg = new String(delivery.getBody(), StandardCharsets.UTF_8);

            if (msg.startsWith(REQUEST_USERLIST)) {
                String requester = msg.substring(REQUEST_USERLIST.length());
                if (!requester.equals(this.userName)) {
                    sendPresence(ANNOUNCE_USER + this.userName);
                }
                return;
            }

            if (msg.startsWith(ANNOUNCE_USER)) {
                String who = msg.substring(ANNOUNCE_USER.length());
                if (!who.equals(this.userName) && onlineUsers.add(who)) {
                    SwingUtilities.invokeLater(() -> usersModel.addElement(who));
                }
                return;
            }

            if (msg.startsWith(JOIN)) {
                String who = msg.substring(JOIN.length());
                if (who.equals(this.userName)) {
                    if (onlineUsers.add(this.userName)) {
                        SwingUtilities.invokeLater(() -> usersModel.addElement(this.userName));
                    }
                } else {
                    if (onlineUsers.add(who)) {
                        SwingUtilities.invokeLater(() -> usersModel.addElement(who));
                    }
                }
                return;
            }

            if (msg.startsWith(LEAVE)) {
                String who = msg.substring(LEAVE.length());
                if (onlineUsers.remove(who)) {
                    SwingUtilities.invokeLater(() -> usersModel.removeElement(who));
                }
            }
        };
    }

    private void sendPresence(String text) {
        try {
            conChannel.basicPublish(
                    presenceExchange,
                    "",
                    null,
                    text.getBytes(StandardCharsets.UTF_8)
            );
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void initGUI() {
        frame = new JFrame("Chat – pokój \"" + this.userRoom + "\" jako \"" + this.userName + "\"");
        frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        frame.setSize(600, 400);
        frame.setLayout(new BorderLayout(5, 5));

        chat = new JTextArea();
        chat.setEditable(false);
        chat.setLineWrap(true);
        chat.setWrapStyleWord(true);
        JScrollPane chatScroll = new JScrollPane(chat);
        chatScroll.setBorder(new EmptyBorder(5, 5, 5, 5));
        frame.add(chatScroll, BorderLayout.CENTER);

        usersModel = new DefaultListModel<>();
        usersList = new JList<>(usersModel);
        usersList.setBorder(BorderFactory.createTitledBorder("Użytkownicy w pokoju"));
        JScrollPane usersScroll = new JScrollPane(usersList);
        usersScroll.setPreferredSize(new Dimension(150, 0));
        frame.add(usersScroll, BorderLayout.EAST);

        JPanel bottomPanel = new JPanel(new BorderLayout(5, 5));
        inputText = new JTextField();
        sendButton = new JButton("Wyślij");
        bottomPanel.add(inputText, BorderLayout.CENTER);
        bottomPanel.add(sendButton, BorderLayout.EAST);
        bottomPanel.setBorder(new EmptyBorder(5, 5, 5, 5));
        frame.add(bottomPanel, BorderLayout.SOUTH);

        ActionListener sendAction = e -> {
            String text = inputText.getText().trim();
            if (text.isEmpty()) return;

            if (text.startsWith("@")) {
                int space = text.indexOf(' ');
                if (space > 1) {
                    String target = text.substring(1, space).trim();
                    String privateMsg = text.substring(space + 1).trim();
                    if (!privateMsg.isEmpty()) {
                        sendPrivateMessage(target, privateMsg);
                        appendToChatArea("[TY → " + target + "] " + privateMsg);
                    }
                } else {
                    appendToChatArea("[SYSTEM] Niepoprawna komenda prywatna. Użyj: @nazwa wiadomość");
                }
            } else {
                sendChatMessage(text);
            }
            inputText.setText("");
        };

        sendButton.addActionListener(sendAction);
        inputText.addActionListener(sendAction);

        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                int choice = JOptionPane.showConfirmDialog(
                        frame,
                        "Czy na pewno chcesz wyjść z czatu?",
                        "Potwierdź wyjście",
                        JOptionPane.YES_NO_OPTION
                );
                if (choice == JOptionPane.YES_OPTION) {
                    try {
                        sendPresence(LEAVE + userName);
                        conChannel.close();
                        connection.close();
                    } catch (Exception ex) {}
                    frame.dispose();
                    System.exit(0);
                }
            }
        });

        SwingUtilities.invokeLater(() -> frame.setVisible(true));
    }

    private void sendChatMessage(String msg) {
        String full = this.userName + ": " + msg;
        try {
            conChannel.basicPublish(
                    chatExchange,
                    "",
                    null,
                    full.getBytes(StandardCharsets.UTF_8)
            );
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void sendPrivateMessage(String target, String msg) {
        String full = this.userName + ": " + msg;
        try {
            conChannel.basicPublish(
                    privateExchange,
                    target,
                    null,
                    full.getBytes(StandardCharsets.UTF_8)
            );
        } catch (IOException e) {
            e.printStackTrace();
            appendToChatArea("[SYSTEM] Nie udało się wysłać prywatnej wiadomości do " + target);
        }
    }

    private void appendToChatArea(String text) {
        SwingUtilities.invokeLater(() -> {
            chat.append(text + "\n");
            chat.setCaretPosition(chat.getDocument().getLength());
        });
    }
}
