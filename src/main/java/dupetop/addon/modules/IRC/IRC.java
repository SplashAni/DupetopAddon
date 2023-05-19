package dupetop.addon.modules.IRC;

import dupetop.addon.Main;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Module;

public class IRC extends Module {
    public IRC() {
        super(Main.CATEGORY,"IRC","");
    }

    @Override
    public void onActivate() {
        info("Connected to irc");
        super.onActivate();
    }
}
