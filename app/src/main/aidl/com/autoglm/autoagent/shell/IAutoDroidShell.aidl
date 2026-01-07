package com.autoglm.autoagent.shell;

interface IAutoDroidShell {
    /**
     * Test if service is alive
     */
    boolean ping();

    /**
     * Inject touch event
     */
    boolean injectTouch(int displayId, int action, int x, int y);

    /**
     * Inject key event
     */
    boolean injectKey(int keyCode);

    /**
     * Take screenshot and return compressed data
     */
    byte[] captureScreen(int displayId);

    /**
     * Start an activity
     */
    boolean startActivity(int displayId, String packageName);

    /**
     * Create a virtual display
     */
    int createVirtualDisplay(String name, int width, int height, int density);

    /**
     * Release a virtual display
     */
    void releaseDisplay(int displayId);

    /**
     * Inject text input
     */
    boolean inputText(int displayId, String text);

    /**
     * Stop the user service
     */
    void destroy();
}
