package org.bluezoo.gumdrop.mailbox.mbox;

import org.bluezoo.gumdrop.mailbox.MailboxStore;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link MboxMailboxFactory}.
 */
public class MboxMailboxFactoryTest {

    private Path tempDir;

    @Before
    public void setUp() throws IOException {
        tempDir = Files.createTempDirectory("mboxfactory");
    }

    @After
    public void tearDown() throws IOException {
        Files.walk(tempDir)
                .sorted(Comparator.reverseOrder())
                .forEach(p -> {
                    try { Files.delete(p); } catch (IOException e) { /* ignore */ }
                });
    }

    @Test
    public void testDefaultConstructor() {
        MboxMailboxFactory factory = new MboxMailboxFactory();
        assertNull(factory.getBaseDirectory());
        assertEquals(MboxMailboxStore.DEFAULT_EXTENSION, factory.getExtension());
    }

    @Test
    public void testPathConstructor() {
        MboxMailboxFactory factory = new MboxMailboxFactory(tempDir);
        assertNotNull(factory.getBaseDirectory());
        assertEquals(MboxMailboxStore.DEFAULT_EXTENSION, factory.getExtension());
    }

    @Test
    public void testPathAndExtensionConstructor() {
        MboxMailboxFactory factory = new MboxMailboxFactory(tempDir, ".mail");
        assertEquals(".mail", factory.getExtension());
    }

    @Test
    public void testFileConstructor() {
        MboxMailboxFactory factory = new MboxMailboxFactory(tempDir.toFile());
        assertNotNull(factory.getBaseDirectory());
    }

    @Test(expected = IllegalArgumentException.class)
    public void testNullPathConstructor() {
        new MboxMailboxFactory((Path) null);
    }

    @Test
    public void testSetBaseDirectoryString() {
        MboxMailboxFactory factory = new MboxMailboxFactory();
        factory.setBaseDirectory(tempDir.toString());
        assertNotNull(factory.getBaseDirectory());
    }

    @Test
    public void testSetBaseDirectoryPath() {
        MboxMailboxFactory factory = new MboxMailboxFactory();
        factory.setBaseDirectory(tempDir);
        assertNotNull(factory.getBaseDirectory());
    }

    @Test(expected = IllegalArgumentException.class)
    public void testSetBaseDirectoryNullString() {
        new MboxMailboxFactory().setBaseDirectory((String) null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testSetBaseDirectoryNullPath() {
        new MboxMailboxFactory().setBaseDirectory((Path) null);
    }

    @Test
    public void testSetExtension() {
        MboxMailboxFactory factory = new MboxMailboxFactory(tempDir);
        factory.setExtension(".mail");
        assertEquals(".mail", factory.getExtension());
    }

    @Test
    public void testCreateStore() {
        MboxMailboxFactory factory = new MboxMailboxFactory(tempDir);
        MailboxStore store = factory.createStore();
        assertNotNull(store);
        assertTrue(store instanceof MboxMailboxStore);
    }

    @Test(expected = IllegalStateException.class)
    public void testCreateStoreWithoutBasedir() {
        new MboxMailboxFactory().createStore();
    }
}
