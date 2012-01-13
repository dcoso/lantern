package org.lantern;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.util.Map;
import java.util.Map.Entry;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.beanutils.PropertyUtils;
import org.apache.commons.httpclient.HttpStatus;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Default implementation of the Lantern API.
 */
public class DefaultLanternApi implements LanternApi {
    
    private final Logger log = LoggerFactory.getLogger(getClass());
    
    /**
     * Enumeration of calls to the Lantern API.
     */
    private enum LanternApiCall {
        SIGNIN,
        SIGNOUT,
        ADDTOWHITELIST,
        REMOVEFROMWHITELIST,
        ADDTRUSTEDPEER,
        REMOVETRUSTEDPEER,
        RESET,
    }

    @Override
    public void processCall(final HttpServletRequest req, 
        final HttpServletResponse resp) {
        final Settings set = LanternHub.settings();
        final String uri = req.getRequestURI();
        final String id = StringUtils.substringAfter(uri, "/api/");
        final LanternApiCall call = LanternApiCall.valueOf(id.toUpperCase());
        log.debug("Got API call {} for full URI: "+uri, call);
        switch (call) {
        case SIGNIN:
            LanternHub.xmppHandler().disconnect();
            final Map<String, String> params = LanternUtils.toParamMap(req);
            String pass = params.remove("password");
            if (StringUtils.isBlank(pass) && set.isSavePassword()) {
                pass = set.getStoredPassword();
                if (StringUtils.isBlank(pass)) {
                    sendError(resp, "No password given and no password stored");
                    return;
                }
            }
            final String rawEmail = params.remove("email");
            final String email;
            if (!rawEmail.contains("@")) {
                email = rawEmail + "@gmail.com";
            } else {
                email = rawEmail;
            }
            changeSetting(resp, "email", email, false);
            changeSetting(resp, "password", pass, false);
            changeSetting(resp, params);
            try {
                LanternHub.xmppHandler().connect();
                if (LanternUtils.shouldProxy()) {
                    // We automatically start proxying upon connect if the 
                    // user's settings say they're in get mode and to use the 
                    // system proxy.
                    Configurator.startProxying();
                }
            } catch (final IOException e) {
                sendError(resp, "Could not login: "+e.getMessage());
            }

            break;
        case SIGNOUT:
            log.info("Signing out");
            LanternHub.xmppHandler().disconnect();
            
            // We stop proxying outside of any user settings since if we're
            // not logged in there's no sense in proxying. Could theoretically
            // use cached proxies, but definitely no peer proxies would work.
            Configurator.stopProxying();
            break;
        case ADDTOWHITELIST:
            LanternHub.whitelist().addEntry(req.getParameter("site"));
            break;
        case REMOVEFROMWHITELIST:
            LanternHub.whitelist().removeEntry(req.getParameter("site"));
            break;
        case ADDTRUSTEDPEER:
            // TODO: Add data validation.
            LanternHub.getTrustedContactsManager().addTrustedContact(
                req.getParameter("email"));
            break;
        case REMOVETRUSTEDPEER:
            // TODO: Add data validation.
            LanternHub.getTrustedContactsManager().removeTrustedContact(
                req.getParameter("email"));
            break;
        case RESET:
            try {
                FileUtils.forceDelete(LanternConstants.DEFAULT_SETTINGS_FILE);
                LanternHub.resetSettings();
            } catch (final IOException e) {
                sendServerError(resp, "Error resetting settings: "+
                   e.getMessage());
            }
            break;
        }
        LanternHub.asyncEventBus().post(new SyncEvent());
        LanternHub.settingsIo().write();
    }

    private void sendServerError(final HttpServletResponse resp, 
        final String msg) {
        try {
            resp.sendError(HttpStatus.SC_INTERNAL_SERVER_ERROR, msg);
        } catch (final IOException e) {
            log.info("Could not send error", e);
        }
    }
    
    private void sendError(final HttpServletResponse resp, final String msg) {
        try {
            resp.sendError(HttpStatus.SC_BAD_REQUEST, msg);
        } catch (final IOException e) {
            log.info("Could not send error", e);
        }
    }

    @Override
    public void changeSetting(final HttpServletRequest req, 
        final HttpServletResponse resp) {
        final Map<String, String> params = LanternUtils.toParamMap(req);
        changeSetting(resp, params);

    }
    
    private void changeSetting(final HttpServletResponse resp,
        final Map<String, String> params) {
        if (params.isEmpty()) {
            sendError(resp, "You must set a setting");
            return;
        }
        final Entry<String, String> keyVal = params.entrySet().iterator().next();
        log.debug("Got keyval: {}", keyVal);
        final String key = keyVal.getKey();
        final String val = keyVal.getValue();
        changeSetting(resp, key, val);
    }
    
    private void changeSetting(final HttpServletResponse resp, final String key, 
            final String val) {
        changeSetting(resp, key, val, true);
    }

    private void changeSetting(final HttpServletResponse resp, final String key, 
        final String val, final boolean determineType) {
        setProperty(LanternHub.settings(), key, val, true, resp, determineType);
        setProperty(LanternHub.settingsChangeImplementor(), key, val, false, 
            resp, determineType);
        resp.setStatus(HttpStatus.SC_OK);
        LanternHub.asyncEventBus().post(new SyncEvent());
        LanternHub.settingsIo().write();
    }

    private void setProperty(final Object bean, 
        final String key, final String val, final boolean logErrors,
        final HttpServletResponse resp, final boolean determineType) {
        log.info("Setting property on {}", bean);
        final Object obj;
        if (determineType) {
            obj = LanternUtils.toTyped(val);
        } else {
            obj = val;
        }
        try {
            PropertyUtils.setSimpleProperty(bean, key, obj);
            //PropertyUtils.setProperty(bean, key, obj);
        } catch (final IllegalAccessException e) {
            sendError(e, resp, logErrors);
        } catch (final InvocationTargetException e) {
            sendError(e, resp, logErrors);
        } catch (final NoSuchMethodException e) {
            sendError(e, resp, logErrors);
        }
    }

    private void sendError(final Exception e, 
        final HttpServletResponse resp, final boolean logErrors) {
        if (logErrors) {
            try {
                resp.sendError(HttpStatus.SC_SERVICE_UNAVAILABLE, e.getMessage());
            } catch (final IOException ioe) {
                log.info("Could not send response", e);
            }
        }
    }

}
