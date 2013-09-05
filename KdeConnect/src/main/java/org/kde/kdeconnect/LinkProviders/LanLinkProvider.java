package org.kde.kdeconnect.LinkProviders;

import android.content.Context;
import android.os.AsyncTask;
import android.util.Log;

import org.apache.mina.core.future.ConnectFuture;
import org.apache.mina.core.future.IoFuture;
import org.apache.mina.core.future.IoFutureListener;
import org.apache.mina.core.service.IoHandler;
import org.apache.mina.core.service.IoHandlerAdapter;
import org.apache.mina.core.session.IoSession;
import org.apache.mina.filter.codec.ProtocolCodecFilter;
import org.apache.mina.filter.codec.textline.LineDelimiter;
import org.apache.mina.filter.codec.textline.TextLineCodecFactory;
import org.apache.mina.transport.socket.nio.NioDatagramAcceptor;
import org.apache.mina.transport.socket.nio.NioSocketAcceptor;
import org.apache.mina.transport.socket.nio.NioSocketConnector;
import org.kde.kdeconnect.ComputerLinks.LanComputerLink;
import org.kde.kdeconnect.NetworkPackage;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.Charset;
import java.util.HashMap;

public class LanLinkProvider extends BaseLinkProvider {

    private final static int port = 1714;

    private Context context;
    private HashMap<String, LanComputerLink> visibleComputers = new HashMap<String, LanComputerLink>();
    private HashMap<Long, LanComputerLink> nioSessions = new HashMap<Long, LanComputerLink>();

    private NioSocketAcceptor tcpAcceptor = null;
    private NioDatagramAcceptor udpAcceptor = null;

    private final IoHandler tcpHandler = new IoHandlerAdapter() {
        @Override
        public void sessionClosed(IoSession session) throws Exception {

            LanComputerLink brokenLink = nioSessions.remove(session.getId());
            if (brokenLink != null) {
                connectionLost(brokenLink);
                brokenLink.disconnect();
                String deviceId = brokenLink.getDeviceId();
                if (visibleComputers.get(deviceId) == brokenLink) {
                    visibleComputers.remove(deviceId);
                }
            }

        }

        @Override
        public void messageReceived(IoSession session, Object message) throws Exception {
            super.messageReceived(session, message);

            //Log.e("LanLinkProvider","Incoming package, address: "+session.getRemoteAddress()).toString());

            String theMessage = (String) message;
            NetworkPackage np = NetworkPackage.unserialize(theMessage);

            LanComputerLink prevLink = nioSessions.get(session.getId());

            if (np.getType().equals(NetworkPackage.PACKAGE_TYPE_IDENTITY)) {
                String myId = NetworkPackage.createIdentityPackage(context).getString("deviceId");
                if (np.getString("deviceId").equals(myId)) {
                    return;
                }
                LanComputerLink link = new LanComputerLink(session, np.getString("deviceId"), LanLinkProvider.this);
                nioSessions.put(session.getId(),link);
                addLink(np, link);
            } else {
                if (prevLink == null) {
                    Log.e("LanLinkProvider","2 Expecting an identity package");
                } else {
                    prevLink.injectNetworkPackage(np);
                }
            }

        }
    };

    private IoHandler udpHandler = new IoHandlerAdapter() {
        @Override
        public void messageReceived(IoSession udpSession, Object message) throws Exception {
            super.messageReceived(udpSession, message);

            //Log.e("LanLinkProvider", "Udp message received (" + message.getClass() + ") " + message.toString());

            NetworkPackage np = null;

            try {
                //We should receive a string thanks to the TextLineCodecFactory filter
                String theMessage = (String) message;
                np = NetworkPackage.unserialize(theMessage);
            } catch (Exception e) {
                e.printStackTrace();
                Log.e("LanLinkProvider", "Could not unserialize package");
            }

            if (np != null) {

                final NetworkPackage identityPackage = np;
                if (!np.getType().equals(NetworkPackage.PACKAGE_TYPE_IDENTITY)) {
                    Log.e("LanLinkProvider", "1 Expecting an identity package");
                    return;
                } else {
                    String myId = NetworkPackage.createIdentityPackage(context).getString("deviceId");
                    if (np.getString("deviceId").equals(myId)) {
                        return;
                    }
                }

                Log.i("LanLinkProvider", "Identity package received, creating link");

                try {
                    final InetSocketAddress address = (InetSocketAddress) udpSession.getRemoteAddress();

                    final NioSocketConnector connector = new NioSocketConnector();
                    connector.setHandler(tcpHandler);
                    //TextLineCodecFactory will split incoming data delimited by the given string
                    connector.getFilterChain().addLast("codec",
                            new ProtocolCodecFilter(
                                    new TextLineCodecFactory(Charset.defaultCharset(), LineDelimiter.UNIX, LineDelimiter.UNIX)
                            )
                    );
                    connector.getSessionConfig().setKeepAlive(true);

                    int tcpPort = np.getInt("tcpPort",port);
                    ConnectFuture future = connector.connect(new InetSocketAddress(address.getAddress(), tcpPort));
                    future.addListener(new IoFutureListener<IoFuture>() {
                        @Override
                        public void operationComplete(IoFuture ioFuture) {
                            IoSession session = ioFuture.getSession();

                            Log.i("LanLinkProvider", "Connection successful: " + session.isConnected());

                            LanComputerLink link = new LanComputerLink(session, identityPackage.getString("deviceId"), LanLinkProvider.this);

                            NetworkPackage np2 = NetworkPackage.createIdentityPackage(context);
                            link.sendPackage(np2);

                            nioSessions.put(session.getId(), link);
                            addLink(identityPackage, link);
                        }
                    });

                } catch (Exception e) {
                    Log.e("LanLinkProvider","Exception!!");
                    e.printStackTrace();
                }

            }
        }
    };

    private void addLink(NetworkPackage identityPackage, LanComputerLink link) {
        String deviceId = identityPackage.getString("deviceId");
        Log.i("LanLinkProvider","addLink to "+deviceId);
        LanComputerLink oldLink = visibleComputers.get(deviceId);
        visibleComputers.put(deviceId, link);
        connectionAccepted(identityPackage, link);
        if (oldLink != null) {
            Log.i("LanLinkProvider","Removing old connection to same device");
            oldLink.disconnect();
            connectionLost(oldLink);
        }
    }

    public LanLinkProvider(Context context) {

        this.context = context;

        //This handles the case when I'm the new device in the network and somebody answers my introduction package
        tcpAcceptor = new NioSocketAcceptor();
        tcpAcceptor.setHandler(tcpHandler);
        tcpAcceptor.getSessionConfig().setKeepAlive(true);
        tcpAcceptor.getSessionConfig().setReuseAddress(true);
        tcpAcceptor.setCloseOnDeactivation(false);
        //TextLineCodecFactory will split incoming data delimited by the given string
        tcpAcceptor.getFilterChain().addLast("codec",
                new ProtocolCodecFilter(
                        new TextLineCodecFactory(Charset.defaultCharset(), LineDelimiter.UNIX, LineDelimiter.UNIX)
                )
        );


        udpAcceptor = new NioDatagramAcceptor();
        udpAcceptor.getSessionConfig().setReuseAddress(true);        //Share port if existing
        //TextLineCodecFactory will split incoming data delimited by the given string
        udpAcceptor.getFilterChain().addLast("codec",
                new ProtocolCodecFilter(
                        new TextLineCodecFactory(Charset.defaultCharset(), LineDelimiter.UNIX, LineDelimiter.UNIX)
                )
        );

    }

    @Override
    public void onStart() {

        //This handles the case when I'm the existing device in the network and receive a "hello" UDP package

        udpAcceptor.setHandler(udpHandler);

        try {
            udpAcceptor.bind(new InetSocketAddress(port));
        } catch(Exception e) {
            Log.e("LanLinkProvider", "Error: Could not bind udp socket");
            e.printStackTrace();
        }

        boolean success = false;
        int tcpPort = port;
        while(!success) {
            try {
                tcpAcceptor.bind(new InetSocketAddress(tcpPort));
                success = true;
            } catch(Exception e) {
                tcpPort++;
            }
        }

        Log.e("LanLinkProvider","Using tcpPort "+tcpPort);

        //I'm on a new network, let's be polite and introduce myself
        final int finalTcpPort = tcpPort;
        new AsyncTask<Void,Void,Void>() {
            @Override
            protected Void doInBackground(Void... voids) {

                try {
                    NetworkPackage identity = NetworkPackage.createIdentityPackage(context);
                    identity.set("tcpPort",finalTcpPort);
                    byte[] b = identity.serialize().getBytes("UTF-8");
                    DatagramPacket packet = new DatagramPacket(b, b.length, InetAddress.getByAddress(new byte[]{-1,-1,-1,-1}), port);
                    DatagramSocket socket = new DatagramSocket();
                    socket.setReuseAddress(true);
                    socket.setBroadcast(true);
                    socket.send(packet);
                    Log.e("LanLinkProvider","Udp identity package sent");
                } catch(Exception e) {
                    e.printStackTrace();
                    Log.e("LanLinkProvider","Sending udp identity package failed");
                }

                return null;

            }

        }.execute();

    }

    @Override
    public void onNetworkChange() {

        onStop();
        onStart();

    }

    @Override
    public void onStop() {

        udpAcceptor.unbind();
        tcpAcceptor.unbind();

    }

    @Override
    public int getPriority() {
        return 1000;
    }

    @Override
    public String getName() {
        return "LanLinkProvider";
    }
}
