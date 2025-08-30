// File: LibraryLoginApp.java
// A first-year–level rewrite: no Optionals, no enums, no streams.
// Functionality preserved: admin & user dashboards, impersonate user, manage books,
// borrow/return with stock, search tables, change password, file persistence (.properties).

import javax.swing.*;
import javax.swing.event.ListSelectionEvent;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableRowSorter;
import javax.swing.RowFilter;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Pattern;

public class LibraryLoginApp {

    // ---------- Config ----------
    private static final String ADMIN_INVITE_CODE = "LIB-ADMIN-2025";
    private static final Path ACCOUNTS_DIR = Paths.get("data", "accounts");
    private static final Path BOOKS_DIR    = Paths.get("data", "books");
    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    // ---------- Helpers ----------

    /** @return current date/time formatted like 2025-08-29T10:45:00 (for saving). */
    static String nowIso() { return LocalDateTime.now().format(ISO); }

    /** @return true if the provided type text equals "admin" (case-insensitive). */
    static boolean isAdminType(String t) { return t != null && t.equalsIgnoreCase("admin"); }

    /** Lowercase helper that handles null safely (returns empty string for null). */
    static String toSafeLower(String s) { return s == null ? "" : s.toLowerCase(); }

    // ---------- Domain ----------
    static class Account {
        String username;
        String password;   // plaintext demo
        String firstName;
        String lastName;
        String type;       // "admin" or "user"
        String createdAt;  // ISO string
        String lastLoginAt; // ISO string or null

        /** Builds an account object; fills defaults for type (user) and createdAt (now) if missing. */
        Account(String username, String password, String firstName, String lastName,
                String type, String createdAt, String lastLoginAt) {
            this.username = username;
            this.password = password;
            this.firstName = firstName;
            this.lastName = lastName;
            this.type = (type == null || type.isEmpty()) ? "user" : type.toLowerCase();
            this.createdAt = (createdAt == null || createdAt.isEmpty()) ? nowIso() : createdAt;
            this.lastLoginAt = lastLoginAt;
        }

        /** Convert this account into key=value properties for saving to disk. */
        Properties toProperties() {
            Properties p = new Properties();
            p.setProperty("username", username);
            p.setProperty("password", password);
            p.setProperty("firstName", firstName);
            p.setProperty("lastName", lastName);
            p.setProperty("type", type);
            p.setProperty("createdAt", createdAt);
            if (lastLoginAt != null && !lastLoginAt.isEmpty()) p.setProperty("lastLoginAt", lastLoginAt);
            return p;
        }

        /** Create an Account object from key=value properties loaded from disk. */
        static Account fromProperties(Properties p) {
            String u = p.getProperty("username", "").trim();
            String pw = p.getProperty("password", "");
            String fn = p.getProperty("firstName", "");
            String ln = p.getProperty("lastName", "");
            String t  = p.getProperty("type", "user").toLowerCase();
            String ca = p.getProperty("createdAt", nowIso());
            String ll = p.getProperty("lastLoginAt", "");
            if (ll.isEmpty()) ll = null;
            return new Account(u, pw, fn, ln, t, ca, ll);
        }
    }

    static class Book {
        int id;
        String title;
        String author;
        int stock; // number of copies left
        HashSet<String> borrowers = new HashSet<String>();          // usernames (lowercase)
        HashMap<String, String> borrowedAtByUser = new HashMap<>(); // usernameLower -> ISO string

        /** Builds a book with id/title/author and a non-negative stock. */
        Book(int id, String title, String author, int stock) {
            this.id = id;
            this.title = title;
            this.author = author;
            this.stock = Math.max(0, stock);
        }

        /** @return true if at least one copy is available to borrow. */
        boolean isAvailable() { return stock > 0; }

        /** Convert this book into key=value properties for saving to disk. */
        Properties toProperties() {
            Properties p = new Properties();
            p.setProperty("id", String.valueOf(id));
            p.setProperty("title", title);
            p.setProperty("author", author);
            p.setProperty("stock", String.valueOf(stock));
            p.setProperty("available", Boolean.toString(isAvailable()));
            if (!borrowers.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                boolean first = true;
                for (String u : borrowers) {
                    if (!first) sb.append(",");
                    sb.append(u);
                    first = false;
                }
                p.setProperty("borrowers", sb.toString());
                for (String u : borrowers) {
                    String t = borrowedAtByUser.get(u);
                    if (t != null) p.setProperty("borrowedAt." + u, t);
                }
            }
            return p;
        }

        /** Create a Book object from key=value properties loaded from disk. */
        static Book fromProperties(Properties p) {
            int id = parseIntSafe(p.getProperty("id", "0"), 0);
            String title = p.getProperty("title", "");
            String author = p.getProperty("author", "");
            String stockStr = p.getProperty("stock", null);
            int stock;
            if (stockStr != null) {
                stock = Math.max(0, parseIntSafe(stockStr, 1));
            } else {
                boolean available = Boolean.parseBoolean(p.getProperty("available", "true"));
                stock = available ? 1 : 0;
            }
            Book b = new Book(id, title, author, stock);
            String borrowersCSV = p.getProperty("borrowers", "");
            if (!borrowersCSV.isEmpty()) {
                String[] arr = borrowersCSV.split(",");
                for (int i = 0; i < arr.length; i++) {
                    String u = toSafeLower(arr[i].trim());
                    if (!u.isEmpty()) {
                        b.borrowers.add(u);
                        String t = p.getProperty("borrowedAt." + u, "");
                        if (!t.isEmpty()) b.borrowedAtByUser.put(u, t);
                    }
                }
            }
            return b;
        }
    }

    /** Parse an int from text; if it fails, return a default value. */
    static int parseIntSafe(String s, int def) {
        try { return Integer.parseInt(s); } catch (Exception e) { return def; }
    }

    // ---------- Persistence: Accounts ----------
    static class AccountStore {
        private final HashMap<String, Account> byUsername = new HashMap<String, Account>();

        /** Make sure folders exist, then load all accounts, and add defaults if empty. */
        AccountStore() {
            ensureDirs();
            loadAll();
            seedIfEmpty();
        }

        /** Create the accounts directory if it doesn't exist. */
        private void ensureDirs() {
            try { Files.createDirectories(ACCOUNTS_DIR); }
            catch (IOException e) { throw new RuntimeException("Cannot create accounts dir", e); }
        }

        /** Read all *.properties account files into memory (byUsername map). */
        private void loadAll() {
            byUsername.clear();
            if (!Files.exists(ACCOUNTS_DIR)) return;
            DirectoryStream<Path> stream = null;
            try {
                stream = Files.newDirectoryStream(ACCOUNTS_DIR, "*.properties");
                for (Path p : stream) {
                    Properties props = new Properties();
                    InputStream in = null;
                    try {
                        in = Files.newInputStream(p);
                        props.load(in);
                        Account acc = Account.fromProperties(props);
                        if (acc.username != null && !acc.username.isEmpty()) {
                            byUsername.put(toSafeLower(acc.username), acc);
                        }
                    } catch (IOException ex) {
                        System.err.println("Failed to load account " + p + ": " + ex.getMessage());
                    } finally {
                        if (in != null) try { in.close(); } catch (IOException ignored) {}
                    }
                }
            } catch (IOException e) {
                System.err.println("Error listing accounts dir: " + e.getMessage());
            } finally {
                if (stream != null) try { stream.close(); } catch (IOException ignored) {}
            }
        }

        /** If there are no accounts, create a default admin and a default user. */
        private void seedIfEmpty() {
            if (byUsername.isEmpty()) {
                create("admin", "admin123", "Default", "Admin", "admin");
                create("user",  "user123",  "Regular", "User",  "user");
            }
        }

        /** @return a list of all accounts (for filling the admin table). */
        java.util.List<Account> listAll() {
            return new ArrayList<Account>(byUsername.values());
        }

        /** Find a single account by username (case-insensitive). */
        Account findByUsername(String username) {
            return byUsername.get(toSafeLower(username));
        }

        /** @return true if a username already exists. */
        boolean exists(String username) {
            return byUsername.containsKey(toSafeLower(username));
        }

        /** Check username/password. If valid, update last login and return the account; otherwise null. */
        Account authenticate(String username, String password) {
            Account acc = byUsername.get(toSafeLower(username));
            if (acc == null) return null;
            if (!Objects.equals(acc.password, password)) return null;
            acc.lastLoginAt = nowIso();
            saveToDisk(acc, acc.username);
            return acc;
        }

        /** Create a new account, save it to disk, and add it to the map. */
        boolean create(String username, String password, String first, String last, String type) {
            String key = toSafeLower(username);
            if (byUsername.containsKey(key)) return false;
            Account acc = new Account(username, password, first, last, type, nowIso(), null);
            if (!saveToDisk(acc, username)) return false;
            byUsername.put(key, acc);
            return true;
        }

        /** Update an existing account (supports renaming the username safely). */
        boolean update(String originalUsername, String newUsername, String password,
                       String first, String last, String type) {
            String oldKey = toSafeLower(originalUsername);
            Account old = byUsername.get(oldKey);
            if (old == null) return false;

            String newKey = toSafeLower(newUsername);
            if (!newKey.equals(oldKey) && byUsername.containsKey(newKey)) return false;

            Account updated = new Account(newUsername, password, first, last, type, old.createdAt, old.lastLoginAt);
            if (!saveToDisk(updated, newUsername)) return false;

            if (!newKey.equals(oldKey)) {
                deleteFileFor(old.username);
                byUsername.remove(oldKey);
            }
            byUsername.put(newKey, updated);
            return true;
        }

        /** Change password after verifying the current password. */
        boolean changePassword(String username, String currentPassword, String newPassword) {
            Account acc = byUsername.get(toSafeLower(username));
            if (acc == null) return false;
            if (!Objects.equals(acc.password, currentPassword)) return false;
            return update(acc.username, acc.username, newPassword, acc.firstName, acc.lastName, acc.type);
        }

        /** Delete an account (remove from memory and delete its file). */
        boolean delete(String username) {
            String key = toSafeLower(username);
            if (!byUsername.containsKey(key)) return false;
            byUsername.remove(key);
            return deleteFileFor(username);
        }

        /** Delete the .properties file for the given username. */
        private boolean deleteFileFor(String username) {
            Path path = ACCOUNTS_DIR.resolve(toSafeLower(username) + ".properties");
            try { Files.deleteIfExists(path); return true; }
            catch (IOException e) { System.err.println("Failed deleting " + path + ": " + e.getMessage()); return false; }
        }

        /** Save an account's data to disk (overwrites existing file). */
        private boolean saveToDisk(Account acc, String filenameUser) {
            Properties props = acc.toProperties();
            Path path = ACCOUNTS_DIR.resolve(toSafeLower(filenameUser) + ".properties");
            OutputStream out = null;
            try {
                out = Files.newOutputStream(path, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                props.store(out, "Library Account");
                return true;
            } catch (IOException e) {
                System.err.println("Failed to save account: " + acc.username + " -> " + e.getMessage());
                return false;
            } finally {
                if (out != null) try { out.close(); } catch (IOException ignored) {}
            }
        }
    }

    // ---------- Persistence: Books ----------
    static class BookStore {
        private final HashMap<Integer, Book> byId = new HashMap<Integer, Book>();

        // Borrow result codes (no enums to keep it first-year friendly)
        static final int BORROW_OK = 0;
        static final int BORROW_NOT_AVAILABLE = 1;
        static final int BORROW_LIMIT_REACHED = 2;
        static final int BORROW_ALREADY_BORROWED = 3;

        /** Ensure folder exists and load all books from disk. */
        BookStore() {
            ensureDirs();
            loadAll();
        }

        /** Create the books directory if it doesn't exist. */
        private void ensureDirs() {
            try { Files.createDirectories(BOOKS_DIR); }
            catch (IOException e) { throw new RuntimeException("Cannot create books dir", e); }
        }

        /** Read all *.properties book files into memory (byId map). */
        private void loadAll() {
            byId.clear();
            if (!Files.exists(BOOKS_DIR)) return;
            DirectoryStream<Path> stream = null;
            try {
                stream = Files.newDirectoryStream(BOOKS_DIR, "*.properties");
                for (Path p : stream) {
                    Properties props = new Properties();
                    InputStream in = null;
                    try {
                        in = Files.newInputStream(p);
                        props.load(in);
                        Book b = Book.fromProperties(props);
                        byId.put(b.id, b);
                    } catch (IOException ex) {
                        System.err.println("Failed to load book " + p + ": " + ex.getMessage());
                    } finally {
                        if (in != null) try { in.close(); } catch (IOException ignored) {}
                    }
                }
            } catch (IOException e) {
                System.err.println("Error listing books dir: " + e.getMessage());
            } finally {
                if (stream != null) try { stream.close(); } catch (IOException ignored) {}
            }
        }

        /** @return all books (used to populate the admin books table). */
        java.util.List<Book> listAll() {
            return new ArrayList<Book>(byId.values());
        }

        /** @return only books with stock > 0 (available to borrow). */
        java.util.List<Book> listAvailable() {
            ArrayList<Book> out = new ArrayList<Book>();
            for (Book b : byId.values()) if (b.isAvailable()) out.add(b);
            return out;
        }

        /** @return all books currently borrowed by the given user. */
        java.util.List<Book> listBorrowedBy(String username) {
            String u = toSafeLower(username);
            ArrayList<Book> out = new ArrayList<Book>();
            for (Book b : byId.values()) if (b.borrowers.contains(u)) out.add(b);
            return out;
        }

        /** Count how many books this user has borrowed (limit enforced elsewhere). */
        int countBorrowedBy(String username) {
            String u = toSafeLower(username);
            int c = 0;
            for (Book b : byId.values()) if (b.borrowers.contains(u)) c++;
            return c;
        }

        /** Compute the next unique book ID (max id + 1). */
        private int nextId() {
            int max = 0;
            for (Integer k : byId.keySet()) if (k > max) max = k;
            return max + 1;
        }

        /** Add a new book, save it, store it in memory, and return it (or null on failure). */
        Book add(String title, String author, int stock) {
            int id = nextId();
            Book b = new Book(id, title, author, Math.max(0, stock));
            if (!saveToDisk(b)) return null;
            byId.put(id, b);
            return b;
        }

        /** Remove a book by id from memory and from disk. */
        boolean remove(int id) {
            byId.remove(id);
            Path path = BOOKS_DIR.resolve(id + ".properties");
            try { Files.deleteIfExists(path); return true; }
            catch (IOException e) { System.err.println("Failed deleting " + path + ": " + e.getMessage()); return false; }
        }

        /** Set a non-negative stock amount for a book and save it. */
        boolean setStock(int id, int newStock) {
            Book b = byId.get(id);
            if (b == null) return false;
            b.stock = Math.max(0, newStock);
            return saveToDisk(b);
        }

        /** Try to borrow a book; returns a BORROW_* code explaining the outcome. */
        int borrow(int id, String username) {
            String u = toSafeLower(username);
            Book b = byId.get(id);
            if (b == null || !b.isAvailable()) return BORROW_NOT_AVAILABLE;
            if (b.borrowers.contains(u)) return BORROW_ALREADY_BORROWED;
            if (countBorrowedBy(u) >= 5) return BORROW_LIMIT_REACHED;

            b.stock = Math.max(0, b.stock - 1);
            b.borrowers.add(u);
            b.borrowedAtByUser.put(u, nowIso());
            if (!saveToDisk(b)) return BORROW_NOT_AVAILABLE;
            return BORROW_OK;
        }

        /** Return a book previously borrowed by the user; saves the updated book. */
        boolean returnBook(int id, String username) {
            String u = toSafeLower(username);
            Book b = byId.get(id);
            if (b == null) return false;
            if (!b.borrowers.contains(u)) return false;

            b.borrowers.remove(u);
            b.borrowedAtByUser.remove(u);
            b.stock += 1;
            return saveToDisk(b);
        }

        /** Save a book's data to disk (overwrites existing file). */
        private boolean saveToDisk(Book b) {
            Properties props = b.toProperties();
            Path path = BOOKS_DIR.resolve(b.id + ".properties");
            OutputStream out = null;
            try {
                out = Files.newOutputStream(path, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                props.store(out, "Book");
                return true;
            } catch (IOException e) {
                System.err.println("Failed to save book: " + b.title + " -> " + e.getMessage());
                return false;
            } finally {
                if (out != null) try { out.close(); } catch (IOException ignored) {}
            }
        }
    }

    // ---------- Frame & Navigation ----------
    static class LoginFrame extends JFrame {
        private final CardLayout card = new CardLayout();
        private final JPanel cards = new JPanel(card);
        private final AccountStore accountStore;
        private final BookStore bookStore;

        private AdminDashboardPanel adminPanel;
        private BooksPanel booksPanel;
        private UserDashboardPanel userPanel;
        private AvailableBooksPanel availablePanel;

        /** Builds the main window, sets up first screens (login/register), sizes and shows the frame. */
        LoginFrame(AccountStore accountStore, BookStore bookStore) {
            super("Library System");
            this.accountStore = accountStore;
            this.bookStore = bookStore;

            LoginPanel login = new LoginPanel(this, accountStore);
            RegisterPanel register = new RegisterPanel(this, accountStore);

            cards.add(login, "login");
            cards.add(register, "register");

            setLayout(new BorderLayout(8, 8));
            add(cards, BorderLayout.CENTER);

            Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
            int w = Math.max(1100, (int)(screen.width * 0.75));
            int h = Math.max(700,  (int)(screen.height * 0.75));
            setMinimumSize(new Dimension(1000, 650));
            setSize(w, h);
            setLocationRelativeTo(null);
            setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            setVisible(true);
        }

        /** Show the login screen. */
        void showLogin()    { card.show(cards, "login"); }

        /** Show the register (create account) screen. */
        void showRegister() { card.show(cards, "register"); }

        /** After successful login, route admin to admin dashboard; user to user home. */
        void onLoginSuccess(Account acc) {
            if (isAdminType(acc.type)) {
                if (adminPanel == null) {
                    adminPanel = new AdminDashboardPanel(this, accountStore, acc);
                    cards.add(adminPanel, "admin");
                } else {
                    adminPanel.setCurrentAdmin(acc);
                    adminPanel.refresh();
                }
                card.show(cards, "admin");
            } else {
                showUserHome(acc, false);
            }
        }

        /** Show the admin books management page (create once, then reuse). */
        void showBooks() {
            if (booksPanel == null) {
                booksPanel = new BooksPanel(this, bookStore);
                cards.add(booksPanel, "books");
            } else {
                booksPanel.refresh();
            }
            card.show(cards, "books");
        }

        /** Return to the admin dashboard and refresh it. */
        void backToAdmin() {
            if (adminPanel != null) adminPanel.refresh();
            card.show(cards, "admin");
        }

        /** Show the logged-in user's dashboard (supports impersonation flag). */
        void showUserHome(Account acc, boolean impersonated) {
            if (userPanel == null) {
                userPanel = new UserDashboardPanel(this, accountStore, bookStore);
                cards.add(userPanel, "userhome");
            }
            userPanel.setUser(acc, impersonated);
            userPanel.refresh();
            card.show(cards, "userhome");
        }

        /** Show the available-books page for the given user (supports impersonation flag). */
        void showAvailableBooks(Account acc, boolean impersonated) {
            if (availablePanel == null) {
                availablePanel = new AvailableBooksPanel(this, bookStore);
                cards.add(availablePanel, "available");
            }
            availablePanel.setContext(acc, impersonated);
            availablePanel.refresh();
            card.show(cards, "available");
        }
    }

    // ---------- UI helpers ----------

    /** Apply some consistent visual tweaks to a JButton. */
    static void styleButton(JButton b) {
        b.setFocusPainted(false);
        b.setRolloverEnabled(false);
        b.setContentAreaFilled(true);
        b.setOpaque(true);
        b.setMargin(new Insets(8, 12, 8, 12));
    }

    // ---------- Login & Register ----------
    static class LoginPanel extends JPanel {
        private final JTextField username = new JTextField(20);
        private final JPasswordField password = new JPasswordField(20);

        /** Build the login page: lays out fields/buttons and wires the actions. */
        LoginPanel(LoginFrame frame, AccountStore store) {
            setLayout(new GridBagLayout());
            GridBagConstraints gc = new GridBagConstraints();
            gc.insets = new Insets(8, 8, 8, 8);
            gc.fill = GridBagConstraints.HORIZONTAL;
            gc.weightx = 1.0;

            JLabel title = new JLabel("Sign in to Library");
            title.setFont(title.getFont().deriveFont(Font.BOLD, 22f));

            JButton loginBtn = new JButton("Sign In");
            styleButton(loginBtn);
            loginBtn.addActionListener(new ActionListener() {
                @Override public void actionPerformed(ActionEvent e) {
                    String u = username.getText().trim();
                    String p = new String(password.getPassword());
                    if (u.isEmpty() || p.isEmpty()) {
                        JOptionPane.showMessageDialog(LoginPanel.this, "Please enter both username and password.");
                        return;
                    }
                    Account a = store.authenticate(u, p);
                    if (a != null) frame.onLoginSuccess(a);
                    else JOptionPane.showMessageDialog(LoginPanel.this, "Invalid username or password.");
                }
            });

            JButton createBtn = new JButton("Create Account");
            styleButton(createBtn);
            createBtn.addActionListener(new ActionListener() {
                @Override public void actionPerformed(ActionEvent e) {
                    frame.showRegister();
                }
            });

            getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke("ENTER"), "LOGIN");
            getActionMap().put("LOGIN", new AbstractAction() {
                @Override public void actionPerformed(ActionEvent e) { loginBtn.doClick(); }
            });

            gc.gridx = 0; gc.gridy = 0; gc.gridwidth = 2; add(title, gc);
            gc.gridwidth = 1;
            gc.gridx = 0; gc.gridy = 1; add(new JLabel("Username"), gc);
            gc.gridx = 1; gc.gridy = 1; add(username, gc);
            gc.gridx = 0; gc.gridy = 2; add(new JLabel("Password"), gc);
            gc.gridx = 1; gc.gridy = 2; add(password, gc);

            JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT));
            buttons.add(loginBtn);
            buttons.add(createBtn);
            gc.gridx = 0; gc.gridy = 3; gc.gridwidth = 2; add(buttons, gc);

            gc.gridx = 0; gc.gridy = 4; gc.weighty = 1.0;
            add(Box.createVerticalGlue(), gc);
        }
    }

    static class RegisterPanel extends JPanel {
        private final JTextField firstName = new JTextField(20);
        private final JTextField lastName = new JTextField(20);
        private final JTextField username = new JTextField(20);
        private final JPasswordField password = new JPasswordField(20);
        private final JPasswordField confirm  = new JPasswordField(20);

        private final JComboBox<String> type = new JComboBox<>(new String[]{"user", "admin"});
        private final JLabel adminCodeLabel = new JLabel("Admin code");
        private final JPasswordField adminCode = new JPasswordField(20);

        /** Build the register page: validates inputs, creates accounts, and toggles admin code field. */
        RegisterPanel(LoginFrame frame, AccountStore store) {
            setLayout(new GridBagLayout());
            GridBagConstraints gc = new GridBagConstraints();
            gc.insets = new Insets(8, 8, 8, 8);
            gc.fill = GridBagConstraints.HORIZONTAL;
            gc.weightx = 1.0;

            JLabel title = new JLabel("Create an Account");
            title.setFont(title.getFont().deriveFont(Font.BOLD, 22f));

            JButton backBtn = new JButton("Back to Sign In");
            styleButton(backBtn);
            backBtn.addActionListener(new ActionListener() {
                @Override public void actionPerformed(ActionEvent e) { frame.showLogin(); }
            });

            JButton createBtn = new JButton("Create Account");
            styleButton(createBtn);
            createBtn.addActionListener(new ActionListener() {
                @Override public void actionPerformed(ActionEvent e) {
                    String f = firstName.getText().trim();
                    String l = lastName.getText().trim();
                    String u = username.getText().trim();
                    String p = new String(password.getPassword());
                    String c = new String(confirm.getPassword());
                    String t = String.valueOf(type.getSelectedItem());

                    if (f.isEmpty() || l.isEmpty() || u.isEmpty() || p.isEmpty() || c.isEmpty()) {
                        JOptionPane.showMessageDialog(RegisterPanel.this, "Please fill out all fields."); return;
                    }
                    if (!p.equals(c)) { JOptionPane.showMessageDialog(RegisterPanel.this, "Passwords do not match."); return; }
                    if (u.length() < 3) { JOptionPane.showMessageDialog(RegisterPanel.this, "Username must be at least 3 characters."); return; }
                    if (p.length() < 4) { JOptionPane.showMessageDialog(RegisterPanel.this, "Password must be at least 4 characters."); return; }
                    if (store.exists(u)) { JOptionPane.showMessageDialog(RegisterPanel.this, "That username is already taken."); return; }
                    if (isAdminType(t)) {
                        String code = new String(adminCode.getPassword());
                        if (!ADMIN_INVITE_CODE.equals(code)) { JOptionPane.showMessageDialog(RegisterPanel.this, "Invalid admin code."); return; }
                    }

                    boolean ok = store.create(u, p, f, l, t);
                    if (ok) { JOptionPane.showMessageDialog(RegisterPanel.this, "Account created! You can sign in now."); frame.showLogin(); }
                    else { JOptionPane.showMessageDialog(RegisterPanel.this, "Could not create account (disk write failed?)."); }
                }
            });

            type.addItemListener(new ItemListener() {
                @Override public void itemStateChanged(ItemEvent e) { updateAdminCodeVisibility(); }
            });

            int row = 0;
            gc.gridx = 0; gc.gridy = row; gc.gridwidth = 2; add(title, gc); row++;
            gc.gridwidth = 1;
            gc.gridx = 0; gc.gridy = row; add(new JLabel("First name"), gc);
            gc.gridx = 1; gc.gridy = row; add(firstName, gc); row++;
            gc.gridx = 0; gc.gridy = row; add(new JLabel("Last name"), gc);
            gc.gridx = 1; gc.gridy = row; add(lastName, gc); row++;
            gc.gridx = 0; gc.gridy = row; add(new JLabel("Username"), gc);
            gc.gridx = 1; gc.gridy = row; add(username, gc); row++;
            gc.gridx = 0; gc.gridy = row; add(new JLabel("Password"), gc);
            gc.gridx = 1; gc.gridy = row; add(password, gc); row++;
            gc.gridx = 0; gc.gridy = row; add(new JLabel("Confirm password"), gc);
            gc.gridx = 1; gc.gridy = row; add(confirm, gc); row++;
            gc.gridx = 0; gc.gridy = row; add(new JLabel("Account type"), gc);
            gc.gridx = 1; gc.gridy = row; add(type, gc); row++;

            gc.gridx = 0; gc.gridy = row; add(adminCodeLabel, gc);
            gc.gridx = 1; gc.gridy = row; add(adminCode, gc); row++;

            JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT));
            buttons.add(createBtn); buttons.add(backBtn);
            gc.gridx = 0; gc.gridy = row; gc.gridwidth = 2; add(buttons, gc); row++;

            gc.gridx = 0; gc.gridy = row; gc.weighty = 1.0; add(Box.createVerticalGlue(), gc);
            updateAdminCodeVisibility();
        }

        /** Show or hide the admin code input based on the selected account type. */
        private void updateAdminCodeVisibility() {
            String t = String.valueOf(type.getSelectedItem());
            boolean isAdmin = isAdminType(t);
            adminCodeLabel.setVisible(isAdmin);
            adminCode.setVisible(isAdmin);
            revalidate(); repaint();
        }
    }

    // ---------- Admin Dashboard ----------
    static class AdminDashboardPanel extends JPanel {
        private final LoginFrame frame;
        private final AccountStore store;
        private Account currentAdmin;

        private final DefaultTableModel model = new DefaultTableModel(
                new Object[]{"#", "First Name", "Last Name", "Username", "Password", "Account Type", "Created", "Last Login"}, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
        private final JTable table = new JTable(model);
        private final TableRowSorter<DefaultTableModel> sorter = new TableRowSorter<>(model);
        private final JComboBox<String> searchColumn = new JComboBox<>(new String[]{
                "First Name","Last Name","Username","Account Type"
        });
        private final JTextField searchText = new JTextField(18);

        private final JTextField fnField = new JTextField(16);
        private final JTextField lnField = new JTextField(16);
        private final JTextField userField = new JTextField(16);
        private final JTextField passField = new JTextField(16);

        /** Build the admin dashboard: search bar, user table, edit form, and action buttons. */
        AdminDashboardPanel(LoginFrame frame, AccountStore store, Account currentAdmin) {
            this.frame = frame; this.store = store; this.currentAdmin = currentAdmin;
            setLayout(new BorderLayout(10, 10));

            JPanel search = new JPanel(new FlowLayout(FlowLayout.LEFT));
            JButton searchBtn = new JButton("Search"); styleButton(searchBtn);
            JButton clearBtn = new JButton("Clear");  styleButton(clearBtn);
            search.add(new JLabel("Search by")); search.add(searchColumn); search.add(searchText); search.add(searchBtn); search.add(clearBtn);
            add(search, BorderLayout.NORTH);

            table.setRowSorter(sorter);
            table.setFillsViewportHeight(true);
            table.setAutoCreateRowSorter(true);
            add(new JScrollPane(table), BorderLayout.CENTER);

            JPanel bottom = new JPanel(new GridBagLayout());
            GridBagConstraints gc = new GridBagConstraints();
            gc.insets = new Insets(6,6,6,6);
            gc.fill = GridBagConstraints.HORIZONTAL; gc.weightx = 1;

            int y = 0;
            gc.gridx=0; gc.gridy=y; bottom.add(new JLabel("First Name:"), gc);
            gc.gridx=1; bottom.add(fnField, gc);
            gc.gridx=2; bottom.add(new JLabel("Last Name:"), gc);
            gc.gridx=3; bottom.add(lnField, gc); y++;

            gc.gridx=0; gc.gridy=y; bottom.add(new JLabel("Username:"), gc);
            gc.gridx=1; bottom.add(userField, gc);
            gc.gridx=2; bottom.add(new JLabel("Password:"), gc);
            gc.gridx=3; bottom.add(passField, gc); y++;

            JButton addBtn = new JButton("Add");
            JButton removeBtn = new JButton("Remove");
            JButton saveBtn = new JButton("Save Changes");
            JButton impersonateBtn = new JButton("Log in as this account");
            JButton manageBooksBtn = new JButton("Manage Books");
            JButton logoutBtn = new JButton("Log out");
            JButton[] bs = new JButton[]{addBtn, removeBtn, saveBtn, impersonateBtn, manageBooksBtn, logoutBtn};
            for (int i=0;i<bs.length;i++) styleButton(bs[i]);

            JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
            buttons.add(addBtn); buttons.add(removeBtn); buttons.add(saveBtn);
            buttons.add(impersonateBtn); buttons.add(manageBooksBtn); buttons.add(logoutBtn);
            gc.gridx=0; gc.gridy=y; gc.gridwidth=4; bottom.add(buttons, gc); y++;
            add(bottom, BorderLayout.SOUTH);

            searchBtn.addActionListener(new ActionListener() {
                @Override public void actionPerformed(ActionEvent e) { applyFilter(); }
            });
            clearBtn.addActionListener(new ActionListener() {
                @Override public void actionPerformed(ActionEvent e) { searchText.setText(""); sorter.setRowFilter(null); }
            });
            table.getSelectionModel().addListSelectionListener(new javax.swing.event.ListSelectionListener() {
                @Override public void valueChanged(ListSelectionEvent e) { onRowSelected(e); }
            });
            addBtn.addActionListener(new ActionListener() { @Override public void actionPerformed(ActionEvent e) { onAdd(); }});
            removeBtn.addActionListener(new ActionListener() { @Override public void actionPerformed(ActionEvent e) { onRemove(); }});
            saveBtn.addActionListener(new ActionListener() { @Override public void actionPerformed(ActionEvent e) { onSave(); }});
            impersonateBtn.addActionListener(new ActionListener() { @Override public void actionPerformed(ActionEvent e) { onImpersonate(); }});
            manageBooksBtn.addActionListener(new ActionListener() { @Override public void actionPerformed(ActionEvent e) { frame.showBooks(); }});
            logoutBtn.addActionListener(new ActionListener() { @Override public void actionPerformed(ActionEvent e) { frame.showLogin(); }});

            refresh();
        }

        /** Update which admin is logged in (used to enforce permissions). */
        void setCurrentAdmin(Account a) { this.currentAdmin = a; }

        /** Rebuild the user table from the latest account data. */
        void refresh() {
            model.setRowCount(0);
            java.util.List<Account> list = store.listAll();
            int i = 1;
            for (int k=0; k<list.size(); k++) {
                Account a = list.get(k);
                model.addRow(new Object[]{
                        i++,
                        a.firstName,
                        a.lastName,
                        a.username,
                        a.password,
                        a.type.toLowerCase(),
                        a.createdAt,
                        a.lastLoginAt == null ? "" : a.lastLoginAt
                });
            }
        }

        /** Apply a case-insensitive text filter to a chosen column in the table. */
        private void applyFilter() {
            String term = searchText.getText().trim();
            if (term.isEmpty()) { sorter.setRowFilter(null); return; }
            String selected = String.valueOf(searchColumn.getSelectedItem());
            int col;
            if ("First Name".equals(selected)) col = 1;
            else if ("Last Name".equals(selected)) col = 2;
            else if ("Username".equals(selected)) col = 3;
            else if ("Account Type".equals(selected)) col = 5;
            else col = 1;
            RowFilter<DefaultTableModel,Object> rf = RowFilter.regexFilter("(?i)" + Pattern.quote(term), col);
            sorter.setRowFilter(rf);
        }

        /** When a row is selected, copy its values into the edit fields. */
        private void onRowSelected(ListSelectionEvent e) {
            if (e.getValueIsAdjusting()) return;
            int vrow = table.getSelectedRow();
            if (vrow < 0) return;
            int row = table.convertRowIndexToModel(vrow);
            fnField.setText(String.valueOf(model.getValueAt(row,1)));
            lnField.setText(String.valueOf(model.getValueAt(row,2)));
            userField.setText(String.valueOf(model.getValueAt(row,3)));
            passField.setText(String.valueOf(model.getValueAt(row,4)));
        }

        /** @return true if the selected table row is an admin account. */
        private boolean isAdminRow(int modelRow) {
            String t = String.valueOf(model.getValueAt(modelRow,5));
            return isAdminType(t);
        }

        /** Create a new regular user using the text fields. */
        private void onAdd() {
            String f = fnField.getText().trim();
            String l = lnField.getText().trim();
            String u = userField.getText().trim();
            String p = passField.getText().trim();
            if (f.isEmpty() || l.isEmpty() || u.isEmpty() || p.isEmpty()) { JOptionPane.showMessageDialog(this, "Fill out First/Last/Username/Password."); return; }
            if (store.exists(u)) { JOptionPane.showMessageDialog(this, "Username already exists."); return; }
            if (store.create(u, p, f, l, "user")) refresh();
            else JOptionPane.showMessageDialog(this, "Failed to add user (disk?).");
        }

        /** Delete the selected user (with safety: cannot delete other admins). */
        private void onRemove() {
            int vrow = table.getSelectedRow();
            if (vrow < 0) { JOptionPane.showMessageDialog(this, "Select a user to remove."); return; }
            int row = table.convertRowIndexToModel(vrow);
            String u = String.valueOf(model.getValueAt(row,3));
            if (isAdminRow(row) && !u.equalsIgnoreCase(currentAdmin.username)) {
                JOptionPane.showMessageDialog(this, "You cannot delete another admin account."); return;
            }
            int ok = JOptionPane.showConfirmDialog(this, "Delete user \"" + u + "\"?", "Confirm", JOptionPane.OK_CANCEL_OPTION);
            if (ok == JOptionPane.OK_OPTION) { if (store.delete(u)) refresh(); else JOptionPane.showMessageDialog(this, "Delete failed."); }
        }

        /** Save edits to the selected user (supports renaming with collision checks). */
        private void onSave() {
            int vrow = table.getSelectedRow();
            if (vrow < 0) { JOptionPane.showMessageDialog(this, "Select a user to edit."); return; }
            int row = table.convertRowIndexToModel(vrow);
            String originalUsername = String.valueOf(model.getValueAt(row,3));
            if (isAdminRow(row) && !originalUsername.equalsIgnoreCase(currentAdmin.username)) {
                JOptionPane.showMessageDialog(this, "You cannot modify another admin account.");
                return;
            }
            String f = fnField.getText().trim();
            String l = lnField.getText().trim();
            String u = userField.getText().trim();
            String p = passField.getText().trim();
            if (f.isEmpty() || l.isEmpty() || u.isEmpty() || p.isEmpty()) { JOptionPane.showMessageDialog(this, "Fill out First/Last/Username/Password."); return; }
            String type = isAdminRow(row) ? "admin" : "user";
            boolean ok = store.update(originalUsername, u, p, f, l, type);
            if (!ok) { JOptionPane.showMessageDialog(this, "Save failed (username taken or disk error)."); return; }
            refresh();
            int newRow = findRowByUsername(u);
            if (newRow >= 0) { int v = table.convertRowIndexToView(newRow); table.getSelectionModel().setSelectionInterval(v, v); }
        }

        /** Helper to locate a table row index by username (case-insensitive). */
        private int findRowByUsername(String u) {
            for (int i=0;i<model.getRowCount();i++) if (String.valueOf(model.getValueAt(i,3)).equalsIgnoreCase(u)) return i;
            return -1;
        }

        /** Impersonate the selected regular user (admins cannot be impersonated here). */
        private void onImpersonate() {
            int vrow = table.getSelectedRow();
            if (vrow < 0) { JOptionPane.showMessageDialog(this, "Select a user to log in as."); return; }
            int row = table.convertRowIndexToModel(vrow);
            if (isAdminRow(row)) { JOptionPane.showMessageDialog(this, "You cannot log in as an admin from here."); return; }
            String u = String.valueOf(model.getValueAt(row,3));
            String pw = String.valueOf(model.getValueAt(row,4));
            Account acc = store.authenticate(u, pw);
            if (acc != null) {
                frame.showUserHome(acc, true);
            } else {
                Account a2 = store.findByUsername(u);
                if (a2 != null) frame.showUserHome(a2, true);
                else JOptionPane.showMessageDialog(this, "User not found.");
            }
        }
    }

    // ---------- Books Panel (Admin add/remove + edit stock) ----------
    static class BooksPanel extends JPanel {
        private final LoginFrame frame;
        private final BookStore store;

        private final DefaultTableModel model = new DefaultTableModel(
                new Object[]{"ID","Title","Author","Stock Left","Status","Borrowers Count"}, 0) {
            @Override public boolean isCellEditable(int r,int c){ return false; }
        };
        private final JTable table = new JTable(model);

        private final JTextField titleField = new JTextField(18);
        private final JTextField authorField = new JTextField(18);
        private final JSpinner stockSpinner = new JSpinner(new SpinnerNumberModel(1, 0, 10000, 1));

        /** Build the books management page: table, small form, and actions (add/update/remove/back). */
        BooksPanel(LoginFrame frame, BookStore store) {
            this.frame = frame; this.store = store;
            setLayout(new BorderLayout(10,10));

            JLabel title = new JLabel("Manage Books");
            title.setFont(title.getFont().deriveFont(Font.BOLD, 20f));
            JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT));
            top.add(title);
            add(top, BorderLayout.NORTH);

            table.setFillsViewportHeight(true);
            table.setAutoCreateRowSorter(true);
            add(new JScrollPane(table), BorderLayout.CENTER);

            JPanel bottom = new JPanel(new GridBagLayout());
            GridBagConstraints gc = new GridBagConstraints();
            gc.insets = new Insets(6,6,6,6);
            gc.fill = GridBagConstraints.HORIZONTAL;
            gc.weightx = 1;

            int y=0;
            gc.gridx=0; gc.gridy=y; bottom.add(new JLabel("Title:"), gc);
            gc.gridx=1; bottom.add(titleField, gc);
            gc.gridx=2; bottom.add(new JLabel("Author:"), gc);
            gc.gridx=3; bottom.add(authorField, gc); y++;

            gc.gridx=0; gc.gridy=y; bottom.add(new JLabel("Quantity Left:"), gc);
            gc.gridx=1; bottom.add(stockSpinner, gc); y++;

            JButton addBtn = new JButton("Add");
            JButton updateStockBtn = new JButton("Update Stock");
            JButton removeBtn = new JButton("Remove");
            JButton backBtn = new JButton("Back to Users");
            styleButton(addBtn); styleButton(updateStockBtn); styleButton(removeBtn); styleButton(backBtn);

            JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
            buttons.add(addBtn); buttons.add(updateStockBtn); buttons.add(removeBtn); buttons.add(backBtn);
            gc.gridx=0; gc.gridy=y; gc.gridwidth=4; bottom.add(buttons, gc); y++;
            add(bottom, BorderLayout.SOUTH);

            table.getSelectionModel().addListSelectionListener(new javax.swing.event.ListSelectionListener() {
                @Override public void valueChanged(ListSelectionEvent e) {
                    if (e.getValueIsAdjusting()) return;
                    int vrow = table.getSelectedRow();
                    if (vrow < 0) return;
                    int row = table.convertRowIndexToModel(vrow);
                    titleField.setText(String.valueOf(model.getValueAt(row,1)));
                    authorField.setText(String.valueOf(model.getValueAt(row,2)));
                    try {
                        int stock = Integer.parseInt(String.valueOf(model.getValueAt(row,3)));
                        stockSpinner.setValue(stock);
                    } catch (NumberFormatException ignored){}
                }
            });

            addBtn.addActionListener(new ActionListener() {
                @Override public void actionPerformed(ActionEvent e) {
                    String t = titleField.getText().trim();
                    String a = authorField.getText().trim();
                    int qty = (Integer) stockSpinner.getValue();
                    if (t.isEmpty() || a.isEmpty()) { JOptionPane.showMessageDialog(BooksPanel.this, "Enter title and author."); return; }
                    Book b = store.add(t, a, qty);
                    if (b == null) JOptionPane.showMessageDialog(BooksPanel.this, "Failed to add book.");
                    refresh();
                }
            });

            updateStockBtn.addActionListener(new ActionListener() {
                @Override public void actionPerformed(ActionEvent e) {
                    int vrow = table.getSelectedRow();
                    if (vrow < 0) { JOptionPane.showMessageDialog(BooksPanel.this, "Select a book first."); return; }
                    int row = table.convertRowIndexToModel(vrow);
                    int id = Integer.parseInt(String.valueOf(model.getValueAt(row,0)));
                    int qty = (Integer) stockSpinner.getValue();
                    if (!store.setStock(id, qty)) JOptionPane.showMessageDialog(BooksPanel.this, "Failed to update stock.");
                    refresh();
                }
            });

            removeBtn.addActionListener(new ActionListener() {
                @Override public void actionPerformed(ActionEvent e) {
                    int vrow = table.getSelectedRow();
                    if (vrow < 0) { JOptionPane.showMessageDialog(BooksPanel.this, "Select a book to remove."); return; }
                    int row = table.convertRowIndexToModel(vrow);
                    int id = Integer.parseInt(String.valueOf(model.getValueAt(row,0)));
                    int ok = JOptionPane.showConfirmDialog(BooksPanel.this, "Delete book ID " + id + "?", "Confirm", JOptionPane.OK_CANCEL_OPTION);
                    if (ok == JOptionPane.OK_OPTION) {
                        if (!store.remove(id)) JOptionPane.showMessageDialog(BooksPanel.this, "Delete failed.");
                        refresh();
                    }
                }
            });

            backBtn.addActionListener(new ActionListener() {
                @Override public void actionPerformed(ActionEvent e) { frame.backToAdmin(); }
            });

            refresh();
        }

        /** Rebuild the books table from current store data. */
        void refresh() {
            model.setRowCount(0);
            java.util.List<Book> list = store.listAll();
            for (int i=0;i<list.size();i++) {
                Book b = list.get(i);
                model.addRow(new Object[]{
                        b.id, b.title, b.author, b.stock,
                        b.isAvailable() ? "available" : "out",
                        b.borrowers.size()
                });
            }
        }
    }

    // ---------- User Dashboard ----------
    static class UserDashboardPanel extends JPanel {
        private final LoginFrame frame;
        private final AccountStore accountStore;
        private final BookStore bookStore;

        private Account user;
        private boolean impersonated;

        private final DefaultTableModel model = new DefaultTableModel(
                new Object[]{"#", "ID", "Title", "Author", "Borrowed At"}, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
        private final JTable table = new JTable(model);

        /** Build the user dashboard: borrowed-books table and actions (borrow/return/change pw/logout). */
        UserDashboardPanel(LoginFrame frame, AccountStore accountStore, BookStore bookStore) {
            this.frame = frame; this.accountStore = accountStore; this.bookStore = bookStore;
            setLayout(new BorderLayout(10,10));

            JLabel title = new JLabel("My Borrowed Books");
            title.setFont(title.getFont().deriveFont(Font.BOLD, 20f));
            add(title, BorderLayout.NORTH);

            table.setFillsViewportHeight(true);
            table.setAutoCreateRowSorter(true);
            add(new JScrollPane(table), BorderLayout.CENTER);

            JPanel bottom = new JPanel(new FlowLayout(FlowLayout.RIGHT));
            JButton borrowBtn = new JButton("Borrow Books");
            JButton returnBtn = new JButton("Return Book");
            JButton changePwBtn = new JButton("Change Password");
            JButton logoutBtn = new JButton("Log out");
            styleButton(borrowBtn); styleButton(returnBtn); styleButton(changePwBtn); styleButton(logoutBtn);

            bottom.add(borrowBtn); bottom.add(returnBtn); bottom.add(changePwBtn); bottom.add(logoutBtn);
            add(bottom, BorderLayout.SOUTH);

            borrowBtn.addActionListener(new ActionListener() { @Override public void actionPerformed(ActionEvent e) {
                frame.showAvailableBooks(user, impersonated);
            }});

            returnBtn.addActionListener(new ActionListener() {
                @Override public void actionPerformed(ActionEvent e) {
                    int vrow = table.getSelectedRow();
                    if (vrow < 0) { JOptionPane.showMessageDialog(UserDashboardPanel.this, "Select a book to return."); return; }
                    int row = table.convertRowIndexToModel(vrow);
                    int id = Integer.parseInt(String.valueOf(model.getValueAt(row, 1)));
                    if (bookStore.returnBook(id, user.username)) refresh();
                    else JOptionPane.showMessageDialog(UserDashboardPanel.this, "Return failed.");
                }
            });

            changePwBtn.addActionListener(new ActionListener() {
                @Override public void actionPerformed(ActionEvent e) { onChangePassword(changePwBtn); }
            });
            logoutBtn.addActionListener(new ActionListener() {
                @Override public void actionPerformed(ActionEvent e) { if (impersonated) frame.backToAdmin(); else frame.showLogin(); }
            });
        }

        /** Tell this panel which user to show, and whether it's admin impersonation. */
        void setUser(Account acc, boolean impersonated) {
            this.user = acc;
            this.impersonated = impersonated;
        }

        /** Refresh the table with the current user's borrowed books. */
        void refresh() {
            model.setRowCount(0);
            java.util.List<Book> mine = bookStore.listBorrowedBy(user.username);
            int i = 1;
            for (int k=0;k<mine.size();k++) {
                Book b = mine.get(k);
                String when = b.borrowedAtByUser.get(toSafeLower(user.username));
                model.addRow(new Object[]{ i++, b.id, b.title, b.author, (when == null ? "" : when) });
            }
        }

        /** Show a small dialog to change password; validates and persists on success. */
        private void onChangePassword(JButton owner) {
            if (impersonated) { JOptionPane.showMessageDialog(this, "Password change is disabled while impersonating."); return; }

            JPasswordField current = new JPasswordField(18);
            JPasswordField newer   = new JPasswordField(18);
            JPasswordField confirm = new JPasswordField(18);

            JPanel p = new JPanel(new GridBagLayout());
            GridBagConstraints gc = new GridBagConstraints();
            gc.insets = new Insets(6,6,6,6);
            gc.fill = GridBagConstraints.HORIZONTAL;
            int y=0;
            gc.gridx=0; gc.gridy=y; p.add(new JLabel("Current password"), gc);
            gc.gridx=1; p.add(current, gc); y++;
            gc.gridx=0; gc.gridy=y; p.add(new JLabel("New password"), gc);
            gc.gridx=1; p.add(newer, gc); y++;
            gc.gridx=0; gc.gridy=y; p.add(new JLabel("Confirm new password"), gc);
            gc.gridx=1; p.add(confirm, gc);

            int res = JOptionPane.showConfirmDialog(owner, p, "Change Password", JOptionPane.OK_CANCEL_OPTION);
            if (res != JOptionPane.OK_OPTION) return;

            String cur = new String(current.getPassword());
            String nw  = new String(newer.getPassword());
            String cf  = new String(confirm.getPassword());
            if (nw.length() < 4) { JOptionPane.showMessageDialog(this, "Password must be at least 4 characters."); return; }
            if (!nw.equals(cf)) { JOptionPane.showMessageDialog(this, "New passwords do not match."); return; }

            boolean ok = accountStore.changePassword(user.username, cur, nw);
            if (ok) JOptionPane.showMessageDialog(this, "Password updated.");
            else JOptionPane.showMessageDialog(this, "Current password is incorrect.");
        }
    }

    // ---------- Available Books (shows only stock>0) ----------
    static class AvailableBooksPanel extends JPanel {
        private final LoginFrame frame;
        private final BookStore store;

        private Account user;
        private boolean impersonated;

        private final DefaultTableModel model = new DefaultTableModel(
                new Object[]{"ID","Title","Author","Stock Left"}, 0) {
            @Override public boolean isCellEditable(int r,int c){ return false; }
        };
        private final JTable table = new JTable(model);
        private final TableRowSorter<DefaultTableModel> sorter = new TableRowSorter<>(model);
        private final JTextField search = new JTextField(20);

        /** Build the "available books" page: search box, table, and borrow/back actions. */
        AvailableBooksPanel(LoginFrame frame, BookStore store) {
            this.frame = frame; this.store = store;
            setLayout(new BorderLayout(10,10));

            JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT));
            JLabel heading = new JLabel("Available Books");
            heading.setFont(heading.getFont().deriveFont(Font.BOLD, 18f));
            top.add(heading);
            top.add(Box.createHorizontalStrut(16));
            top.add(new JLabel("Search:"));
            top.add(search);
            JButton go = new JButton("Search"); styleButton(go);
            JButton clear = new JButton("Clear"); styleButton(clear);
            top.add(go); top.add(clear);
            add(top, BorderLayout.NORTH);

            table.setFillsViewportHeight(true);
            table.setRowSorter(sorter);
            add(new JScrollPane(table), BorderLayout.CENTER);

            JPanel bottom = new JPanel(new FlowLayout(FlowLayout.RIGHT));
            JButton borrow = new JButton("Borrow Selected");
            JButton back = new JButton("Back");
            styleButton(borrow); styleButton(back);
            bottom.add(borrow); bottom.add(back);
            add(bottom, BorderLayout.SOUTH);

            go.addActionListener(new ActionListener() { @Override public void actionPerformed(ActionEvent e) { applyFilter(); }});
            clear.addActionListener(new ActionListener() { @Override public void actionPerformed(ActionEvent e) { search.setText(""); sorter.setRowFilter(null); }});

            borrow.addActionListener(new ActionListener() {
                @Override public void actionPerformed(ActionEvent e) {
                    int vrow = table.getSelectedRow();
                    if (vrow < 0) { JOptionPane.showMessageDialog(AvailableBooksPanel.this, "Select a book to borrow."); return; }
                    int row = table.convertRowIndexToModel(vrow);
                    int id = Integer.parseInt(String.valueOf(model.getValueAt(row,0)));

                    int r = store.borrow(id, user.username);
                    if (r == BookStore.BORROW_OK) {
                        JOptionPane.showMessageDialog(AvailableBooksPanel.this, "Borrowed successfully.");
                        frame.showUserHome(user, impersonated);
                    } else if (r == BookStore.BORROW_LIMIT_REACHED) {
                        JOptionPane.showMessageDialog(AvailableBooksPanel.this, "Limit reached: you can borrow up to 5 books.");
                    } else if (r == BookStore.BORROW_ALREADY_BORROWED) {
                        JOptionPane.showMessageDialog(AvailableBooksPanel.this, "You already borrowed this book.");
                    } else {
                        JOptionPane.showMessageDialog(AvailableBooksPanel.this, "Book is not available.");
                    }
                    refresh();
                }
            });

            back.addActionListener(new ActionListener() { @Override public void actionPerformed(ActionEvent e) { frame.showUserHome(user, impersonated); }});
        }

        /** Provide the user context (and whether this is admin impersonation). */
        void setContext(Account user, boolean impersonated) { this.user = user; this.impersonated = impersonated; }

        /** Fill the table with only books that have stock remaining. */
        void refresh() {
            model.setRowCount(0);
            java.util.List<Book> list = store.listAvailable();
            for (int i=0;i<list.size();i++) {
                Book b = list.get(i);
                model.addRow(new Object[]{ b.id, b.title, b.author, b.stock });
            }
        }

        /** Apply a simple case-insensitive text filter to the table rows. */
        private void applyFilter() {
            String term = search.getText().trim();
            if (term.isEmpty()) { sorter.setRowFilter(null); return; }
            RowFilter<DefaultTableModel, Object> rf = RowFilter.regexFilter("(?i)" + Pattern.quote(term));
            sorter.setRowFilter(rf);
        }
    }

    // ---------- Main ----------

    /** Program entry point: load stores and open the main window. */
    public static void main(String[] args) {
        AccountStore accounts = new AccountStore();
        BookStore books = new BookStore();
        new LoginFrame(accounts, books);
    }
}
