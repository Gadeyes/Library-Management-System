LibraryLoginApp
===============

What is this?
-------------
A small Java Swing desktop app that simulates a library system. It has:
- Login / Register screens
- Admin dashboard (manage users, manage books, impersonate a user)
- User dashboard (borrow / return books, change password)
- Book "stock" (quantity) tracking
- Data saved to simple .properties text files on disk (no database)


How to run (Command line)
-------------------------
1) Open a terminal/command prompt in the project folder (where LibraryLoginApp.java is).
2) Compile:
   javac -encoding UTF-8 LibraryLoginApp.java
3) Run:
   java LibraryLoginApp

On first run the app will create a "data" folder and seed two accounts.


Default accounts and codes
--------------------------
- Admin invite code (required when registering an admin):
  LIB-ADMIN-2025

- Seeded accounts (created automatically if no accounts exist):
  • admin / admin123   (admin)
  • user  / user123    (regular user)

- Borrow limit: a user can borrow up to 5 books at a time.


Where is data stored?
---------------------
Plain text .properties files under the "data" folder:

- Accounts: data/accounts/<username>.properties
  Example keys: username, password, firstName, lastName, type, createdAt, lastLoginAt

- Books:    data/books/<id>.properties
  Example keys: id, title, author, stock, available, borrowers, borrowedAt.<username>

You can reset the app by closing it and deleting the "data" folder.


How to use the app
------------------
1) Launch the app.
2) Log in:
   - Use admin/admin123 to manage users and books.
   - Use user/user123 to try borrowing/returning.
3) As Admin:
   - Manage Users: add/remove/edit users; impersonate a user to see their view.
   - Manage Books: add books, adjust stock, remove books.
4) As User:
   - "Borrow Books" shows only books with stock > 0.
   - Select a book and click "Borrow Selected".
   - Return a book from "My Borrowed Books".
   - Change password from your dashboard.








