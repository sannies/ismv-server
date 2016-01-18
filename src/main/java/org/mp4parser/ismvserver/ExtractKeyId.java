package org.mp4parser.ismvserver;

import org.mp4parser.IsoFile;
import org.mp4parser.boxes.microsoft.PiffTrackEncryptionBox;
import org.mp4parser.tools.Path;

import java.io.IOException;
import java.util.List;

/**
 * Created by sannies on 18.01.2016.
 */
public class ExtractKeyId {
    public static void main(String[] args) throws IOException {
        IsoFile isoFile = new IsoFile(args[0]);
        List<PiffTrackEncryptionBox> piffTencs =  Path.getPaths(isoFile, "moov/trak/mdia/minf/stbl/stsd/enca/sinf/schi/uuid");
        for (PiffTrackEncryptionBox piffTenc : piffTencs) {
            System.err.println(piffTenc.getDefault_KID());
        }
    }
}
