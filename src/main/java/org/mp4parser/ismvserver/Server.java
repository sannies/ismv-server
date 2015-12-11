package org.mp4parser.ismvserver;

import org.eclipse.jetty.server.handler.HandlerList;
import org.eclipse.jetty.server.handler.ResourceHandler;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.servlets.CrossOriginFilter;
import org.eclipse.jetty.util.resource.Resource;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;

import javax.servlet.DispatcherType;
import java.io.File;
import java.util.EnumSet;

/**
 * Created by sannies on 10.12.2015.
 */
public class Server {
    @Option(name = "--port", usage = "Port of the Live Server")
    int port = 8080;

    @Option(name = "--data-directory", usage = "Data directory of the IIS Push Receiver")
    File dataDir = new File("/opt/livestreams");


    public static void main(String[] args) throws Exception {
        Server hlsServer = new Server();
        CmdLineParser cmdLineParser = new CmdLineParser(hlsServer);
        try {
            cmdLineParser.parseArgument(args);
            System.exit(hlsServer.run());
        } catch (CmdLineException e) {
            cmdLineParser.printUsage(System.err);
            System.exit(13);
        } catch (Exception e) {
            System.err.println(e.getMessage());
            System.exit(14);
        }

    }

    public int run() throws Exception {
        org.eclipse.jetty.server.Server server = new org.eclipse.jetty.server.Server(port);
        HandlerList handlerList = new HandlerList();

        ServletContextHandler servletContextHandler = new ServletContextHandler();
        FilterHolder cof = new FilterHolder(new CrossOriginFilter());
        cof.setInitParameter(
                CrossOriginFilter.EXPOSED_HEADERS_PARAM,
                "Date");
        servletContextHandler.addFilter(cof, "/*", EnumSet.of(DispatcherType.INCLUDE, DispatcherType.REQUEST));
        servletContextHandler.addServlet(new ServletHolder(new SmoothStreamingServlet(dataDir)), "/*");
        ResourceHandler resourceHandler = new ResourceHandler();
        resourceHandler.setBaseResource(Resource.newClassPathResource("supportfiles"));
        handlerList.addHandler(resourceHandler);
        handlerList.addHandler(servletContextHandler);
        server.setHandler(handlerList);

        server.start();
        server.dumpStdErr();
        server.join();
        return 0;
    }
}
