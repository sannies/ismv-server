package org.mp4parser.ismvserver;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.mp4parser.Box;
import org.mp4parser.IsoFile;
import org.mp4parser.boxes.iso14496.part12.MovieFragmentBox;
import org.mp4parser.boxes.iso14496.part12.TrackFragmentRandomAccessBox;
import org.mp4parser.tools.IsoTypeReader;
import org.mp4parser.tools.Path;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.print.Doc;
import javax.servlet.Servlet;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.stream.XMLStreamException;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.WritableByteChannel;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Created by sannies on 10.12.2015.
 */
public class SmoothStreamingServlet extends HttpServlet {
    private File dataDir;

    Pattern clientAccess = Pattern.compile("^/clientaccesspolicy\\.xml$");
    Pattern crossDomain = Pattern.compile("^/crossdomain\\.xml$");
    Pattern manifestPattern = Pattern.compile("^/(.*)/(.*\\.ism)/Manifest$");
    Pattern segmentPattern = Pattern.compile("^/(.*)/(.*\\.ism)/QualityLevels\\((.*)\\)/Fragments\\((.*)=(.*)\\)$");


    public SmoothStreamingServlet(File dataDir) {
        this.dataDir = dataDir;
    }

    DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
    XPathFactory xpf = XPathFactory.newInstance();

    private synchronized DocumentBuilder getDocumentBuilder() throws IOException {
        try {
            return dbf.newDocumentBuilder();
        } catch (ParserConfigurationException e) {
            throw new IOException(e);
        }
    }

    private synchronized XPath getXPath() {
        return xpf.newXPath();
    }

    File getServerManifest(String path, String name) throws IOException {
        File f = dataDir;
        String[] pathElements = path.split("/");
        //Collections.reverse(Arrays.asList(path));
        for (String s : pathElements) {
            f = new File(f, s);
        }
        return new File(f, name);
    }

    Document parseXml(File f) throws IOException {
        DocumentBuilder db = getDocumentBuilder();
        try {
            return db.parse(f);
        } catch (SAXException e) {
            throw new IOException(e);
        }
    }


    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        try {
            Matcher m = manifestPattern.matcher(req.getPathInfo());
            if (m.matches()) {
                File f = getServerManifest(m.group(1), m.group(2));
                Document d = parseXml(f);
                String clientManifestRelativePath = (String) getXPath().evaluate(
                        "smil/head/meta[@name='clientManifestRelativePath']/@content", d, XPathConstants.STRING);

                File clientManifestFile = new File(f.getParentFile(), clientManifestRelativePath);
                byte[] clientManifest = FileUtils.readFileToByteArray(clientManifestFile);
                resp.setContentType("application/vnd.ms-sstr+xml");
                resp.setContentLength(clientManifest.length);
                resp.getOutputStream().write(clientManifest);
                return;
            }
            m = segmentPattern.matcher(req.getPathInfo());
            if (m.matches()) {
                File f = getServerManifest(m.group(1), m.group(2));
                Document d = parseXml(f);
                Node item = (Node) getXPath().evaluate(
                        "smil/body/switch/*[@systemBitrate='" + m.group(3) + "']/param[@name='trackName'][@value='" + m.group(4) + "']/..", d, XPathConstants.NODE);
                String src = item.getAttributes().getNamedItem("src").getTextContent();
                long time = Long.parseLong(m.group(5));
                long trackId = Long.parseLong((String) getXPath().evaluate("param[@name='trackID']/@value", item, XPathConstants.STRING));
                File ismvFile = new File(f.getParentFile(), src);
                try (FileChannel raf = new FileInputStream(ismvFile).getChannel()) {
                    raf.position(raf.size() - 4);
                    ByteBuffer negativeOffset = ByteBuffer.allocate(4);
                    raf.read(negativeOffset);
                    raf.position(raf.size() - IsoTypeReader.readUInt32((ByteBuffer) negativeOffset.position(0)));
                    IsoFile mfraPart = new IsoFile(raf);
                    List<TrackFragmentRandomAccessBox> tfras = Path.getPaths(mfraPart, "mfra/tfra");
                    TrackFragmentRandomAccessBox tfra = null;
                    for (TrackFragmentRandomAccessBox _tfra : tfras) {
                        if (trackId == _tfra.getTrackId()) {
                            tfra = _tfra;
                            break;
                        }
                    }
                    assert tfra != null;
                    for (TrackFragmentRandomAccessBox.Entry entry : tfra.getEntries()) {
                        if (entry.getTime() == time) {
                            raf.position(entry.getMoofOffset());
                            ByteBuffer moofSize = ByteBuffer.allocate(4);
                            raf.read(moofSize);
                            int s1 = (int) IsoTypeReader.readUInt32((ByteBuffer) moofSize.rewind());
                            moofSize.rewind();
                            ByteBuffer moof = ByteBuffer.allocate(s1 - 4); // length bytes already read
                            raf.read(moof);
                            moof.rewind();
                            ByteBuffer mdatSize = ByteBuffer.allocate(4);
                            raf.read(mdatSize);
                            int s2 = (int) IsoTypeReader.readUInt32((ByteBuffer) mdatSize.rewind());
                            mdatSize.rewind();
                            ByteBuffer mdat = ByteBuffer.allocate(s2 - 4); // length bytes already read
                            raf.read(mdat);
                            mdat.rewind();
                            resp.setContentType("video/mp4");
                            resp.setContentLength( s1 + s2);
                            WritableByteChannel wbc = Channels.newChannel(resp.getOutputStream());
                            wbc.write(moofSize);
                            wbc.write(moof);
                            wbc.write(mdatSize);
                            wbc.write(mdat);
                            return;
                        } else if (entry.getTime() > time) {
                            System.err.println("Didn't find moof for track/time " + trackId + "/" + time + " in " + ismvFile);
                            resp.sendError(HttpServletResponse.SC_NOT_FOUND);
                        }
                    }
                }


            }
            m = clientAccess.matcher(req.getPathInfo());
            if (m.matches()) {
                IOUtils.copy(SmoothStreamingServlet.class.getResourceAsStream("/clientaccesspolicy.xml"), resp.getOutputStream());
                return;
            }
            m = crossDomain.matcher(req.getPathInfo());
            if (m.matches()) {
                IOUtils.copy(SmoothStreamingServlet.class.getResourceAsStream("/crossdomain.xml"), resp.getOutputStream());
                return;
            }
        } catch (XPathExpressionException e) {
            e.printStackTrace();
        }
        resp.sendError(HttpServletResponse.SC_NOT_FOUND);
    }

}
