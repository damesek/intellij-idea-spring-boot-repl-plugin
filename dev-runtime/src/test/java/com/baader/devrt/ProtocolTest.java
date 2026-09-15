package com.baader.devrt;

import hu.baader.repl.protocol.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ProtocolTest {
    @TempDir Path temp;
    @Test void utf8AndNestedValuesSurviveSingleByteReads() throws Exception {
        Map<String,Object> frame = Map.of("id","árvíz 🧪","status",List.of("done"),"ops",Map.of("eval",Map.of()),"number",42L);
        var output = new ByteArrayOutputStream(); Bencode.write(frame, output);
        InputStream fragmented = new FilterInputStream(new ByteArrayInputStream(output.toByteArray())) {
            @Override public int read(byte[] b, int off, int len) throws IOException { return super.read(b, off, Math.min(1,len)); }
        };
        assertEquals(frame, Bencode.read(fragmented)); assertNull(Bencode.read(fragmented));
    }
    @Test void consecutiveFramesRemainAligned() throws Exception {
        var output = new ByteArrayOutputStream();
        for (int i=0; i<1000; i++) Bencode.write(Map.of("id",Integer.toString(i),"value","ő 🧪","status",List.of("done")),output);
        var input = new ByteArrayInputStream(output.toByteArray());
        for (int i=0; i<1000; i++) assertEquals(Integer.toString(i), Bencode.read(input).get("id"));
        assertNull(Bencode.read(input));
    }
    @Test void malformedTruncatedOversizedAndDuplicateMessagesFail() {
        for (String frame : List.of("d1:a3:xe", "d1:ai+1ee", "d1:ai-0ee", "d1:a01:xe", "d1:a9999999999:x", "d1:a1:x1:a1:ye", "d" + "l".repeat(40)))
            assertThrows(IOException.class, () -> Bencode.read(new ByteArrayInputStream(frame.getBytes(StandardCharsets.UTF_8))),frame);
        assertThrows(IOException.class, () -> Bencode.write(Map.of("code","x".repeat(Bencode.MAX_MESSAGE_BYTES + 1)),new ByteArrayOutputStream()));
        assertThrows(IOException.class, () -> Bencode.read(new ByteArrayInputStream(new byte[]{'d','1',':','a','1',':',(byte)0xff,'e'})));
    }
    @Test void statusCannotBeDroppedByFlattening() {
        var message=Map.<String,Object>of("status",List.of("error","done"),"err","failed");
        assertTrue(ReplProtocol.done(message)); assertTrue(ReplProtocol.error(message));
        assertTrue(ReplProtocol.strings(message).get("status").contains("error"));
    }
    @Test void endpointRoundTripAndPrivatePermissions() throws Exception {
        Path file=temp.resolve("endpoint.properties");
        var endpoint = new EndpointFile.Endpoint(1234,"x".repeat(64),ProcessHandle.current().pid());
        EndpointFile.write(file,endpoint); assertEquals(endpoint,EndpointFile.read(file));
        if (Files.getFileStore(file).supportsFileAttributeView("posix"))
            assertEquals(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"),Files.getPosixFilePermissions(file));
        Files.writeString(file,"port=1234\ntoken=short\npid=1\nprotocol=1");
        assertThrows(IOException.class, () -> EndpointFile.read(file));
    }
    @Test void endpointSymlinksAreRejected() throws Exception {
        Path target=temp.resolve("original"); Files.writeString(target,"keep");
        Path link=temp.resolve("link"); Files.createSymbolicLink(link,target);
        assertThrows(IOException.class, () -> EndpointFile.write(link,new EndpointFile.Endpoint(1234,"x".repeat(64),1)));
        assertThrows(IOException.class, () -> EndpointFile.read(link));
        assertEquals("keep",Files.readString(target));
    }
    @Test void authenticationIsRequiredAndTokenIsNeverReflected() {
        try (var server = new MiniNreplServer(0,"x".repeat(64))) {
            assertFalse(server.authenticated(Map.of("op","describe")));
            assertFalse(server.authenticated(Map.of("token","wrong")));
            assertTrue(server.authenticated(Map.of("token","x".repeat(64))));
        }
    }
}
