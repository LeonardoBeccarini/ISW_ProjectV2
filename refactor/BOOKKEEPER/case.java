import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.jms.JMSException;
import javax.jms.MessageListener;

import org.apache.hedwig.jms.message.MessageImpl;
import org.apache.hedwig.jms.message.MessageUtil;
import org.apache.hedwig.jms.selector.SelectorParser;
import org.apache.hedwig.jms.util.DebugUtil;
import org.apache.hedwig.jms.util.ReceivedMessage;
import org.apache.hedwig.jms.util.Subscription;
import org.apache.hedwig.jms.util.TransactedReceiveOperation;
import org.jdom2.Element; // remove if not needed by your selector Node type
// NOTE: keep the same Node type you have in the original file (replace if needed)
import org.apache.hedwig.jms.selector.Node;

// This file is intentionally a "sandbox": it does not need to compile as a full project.
// It only needs to be parseable by JavaParser for metric extraction.

    // ======= BEFORE (original) =======
    private void dispatchReceivedMessagesToSubscribers_before(MessageListener sessionMessageListener,
                                                              List<ReceivedMessage> messageListCopy,
                                                              List<TransactedReceiveOperation> rolledbackMessageListCopy) {
        assert null != messageListCopy;

        // Doing it before processing messageList.
        handleRollbackInDispatch(rolledbackMessageListCopy);

        for (final ReceivedMessage receivedMessage : messageListCopy){

            if (isClosed()) break;

            // It is possible that previous listener rolledback transaction ... check that before
            // delivering the other messages !
            // Else we will mess up the oder of message delivery.
            {
                int retryCount = 0;
                while (retryCount < RETRY_DISPATCH_TO_TRANSACTION_ATTEMPTS){
                    if (! handleRollbackInDispatch(null)) break;
                    retryCount ++;
                }
                if (RETRY_DISPATCH_TO_TRANSACTION_ATTEMPTS == retryCount){
                    // we cant do much - close session and abort.
                    try {
                        SessionImpl.this.close();
                    } catch (JMSException e) {
                        if (logger.isDebugEnabled()) logger.debug("Exception closing session", e);
                    }
                    return ;
                }
            }

            final Subscription subscription = createSubscription(receivedMessage.destinationType,
                    receivedMessage.originalMessage.getSourceName(), receivedMessage.originalMessage.getSubscriberId());

            // COW - so no need to worry about concurrent-mod's or inconsistent states - other than
            // potential stale state,
            // which is fine since MessageConsumer's are essentially immutable from basic state point
            // of view (subscriberId, destination).
            CopyOnWriteArrayList<? extends MessageConsumer> subscriberList =
                    subscriptions.getSubscribers(subscription);
            if (null == subscriberList) continue;

            if (! subscriberList.listIterator().hasNext()) continue;

            // For selector support - pick up the last register
            Node ast = subscriptions.getSelectorExpression(subscription);
            if (logger.isTraceEnabled()) logger.trace("subscription : " + subscription + ", selector : " + ast);
            if (null != ast){
                // final Boolean value = SelectorParser.evaluateSelector(ast, receivedMessage.originalMessage);
                final Boolean value = SelectorParser.evaluateSelector(ast, receivedMessage.msg);

                if (null == value){
                    if (logger.isDebugEnabled()) {
                        logger.debug("Unable to evaluate selector ? ... ignoring message");
                        logger.debug("Message : " + receivedMessage.msg);
                    }
                    receivedMessage.originalMessage.getAckRunnable().run();
                    continue;
                }
                if (! Boolean.TRUE.equals(value)){
                    if (logger.isTraceEnabled()) logger.trace("Selector DID NOT evaluate to true (" +
                            value + "), ignore message ignoring message");
                    receivedMessage.originalMessage.getAckRunnable().run();
                    continue;
                }
            }


            if (null != sessionMessageListener){
                // Since there was atleast one subscriber when we started this loop (which might
                // not be case anymore, but that is just an uncontrollable harmless race)
                // we can send it to messageListener for the session.
                if (logger.isTraceEnabled()) logger.trace("Dispatching " + receivedMessage.originalMessage +
                        " to session listener");

                if (isMessageExpired(receivedMessage.originalMessage)){
                    // message already expired.
                    // This means we acknowledge for all subscribers with this subscription id ...
                    receivedMessage.originalMessage.getAckRunnable().run();
                    continue;
                }

                try {
                    final MessageImpl message = MessageUtil.createCloneForDispatch(this,
                            receivedMessage.originalMessage, receivedMessage.originalMessage.getSourceName(),
                            receivedMessage.originalMessage.getSubscriberId());
                    deliverToListener(sessionMessageListener, receivedMessage, message, false);
                } catch (JMSException e) {
                    // Unexpected not to be able to clone ...
                    if (logger.isDebugEnabled()) {
                        logger.debug("Unexpected exception trying to process message");
                        DebugUtil.dumpJMSStacktrace(logger, e);
                    }
                }
                continue;
            }

            for (final MessageConsumer subscriber : subscriberList){
                if (isClosed()) break;
                try {
                    final MessageListener subscriberListener = subscriber.getMessageListener();
                    // Clone - since each subscrber can modify the message.  We are optimizing this
                    // to clone only if subscriberList
                    // has more than one subscriber to avoid the (potentially) expensive creation.
                    if (getNoLocal(subscription, subscriber)){
                        if (isLocallyPublished(receivedMessage.originalMessage.getJMSMessageID())){
                            // This means we acknowledge for all subscribers with this subscription id ...
                            receivedMessage.originalMessage.getAckRunnable().run();
                            continue;
                        }
                    }
                    if (isMessageExpired(receivedMessage.originalMessage)){
                        receivedMessage.originalMessage.getAckRunnable().run();
                        continue;
                    }

                    final MessageImpl message = MessageUtil.createCloneForDispatch(this,
                            receivedMessage.originalMessage, receivedMessage.originalMessage.getSourceName(),
                            receivedMessage.originalMessage.getSubscriberId());

                    if (logger.isTraceEnabled()) logger.trace("Dispatching " + message +
                            " to subscriber subscriberListener ? " + (subscriberListener != null));

                    if (null != subscriberListener) {
                        deliverToListener(subscriberListener, receivedMessage, message, false);
                    }
                    else {
                        sessionFacade.enqueueReceivedMessage(subscriber,
                                new ReceivedMessage(receivedMessage.originalMessage, message,
                                        receivedMessage.destinationType), false);
                    }

                    if (logger.isTraceEnabled()) logger.trace("Dispatching " + message +
                            " to subscriberListener ? " + (subscriberListener != null) + ", DONE");
                } catch (JMSException e) {
                    // Unexpected not to be able to clone ...
                    if (logger.isDebugEnabled()) {
                        logger.debug("Unexpected exception trying to process message", e);
                    }
                    continue ;
                }
            }
        }

        if (logger.isTraceEnabled()) logger.trace("dispatchReceivedMessagesToSubscribers() DONE");
    }

    // ======= AFTER (refactored) =======
    private void dispatchReceivedMessagesToSubscribers_after(
            MessageListener sessionMessageListener,
            List<ReceivedMessage> messageListCopy,
            List<TransactedReceiveOperation> rolledbackMessageListCopy) {

        assert messageListCopy != null;

        handleRollbackInDispatch(rolledbackMessageListCopy);

        for (final ReceivedMessage receivedMessage : messageListCopy) {
            if (isClosed()) {
                break;
            }

            if (!ensureRollbackDrainedOrAbort()) {
                return;
            }

            final Subscription subscription = createSubscription(
                    receivedMessage.destinationType,
                    receivedMessage.originalMessage.getSourceName(),
                    receivedMessage.originalMessage.getSubscriberId());

            final CopyOnWriteArrayList<? extends MessageConsumer> subscriberList =
                    subscriptions.getSubscribers(subscription);

            if (isSubscriberListEmpty(subscriberList)) {
                continue;
            }

            if (shouldSkipBySelector(subscription, receivedMessage)) {
                continue;
            }

            if (sessionMessageListener != null) {
                dispatchToSessionListener(sessionMessageListener, receivedMessage);
                continue;
            }

            dispatchToSubscribers(subscriberList, subscription, receivedMessage);
        }

        if (logger.isTraceEnabled()) {
            logger.trace("dispatchReceivedMessagesToSubscribers() DONE");
        }
    }

    // ======= Helpers (extracted) =======

    private boolean ensureRollbackDrainedOrAbort() {
        int retryCount = 0;
        while (retryCount < RETRY_DISPATCH_TO_TRANSACTION_ATTEMPTS) {
            if (!handleRollbackInDispatch(null)) {
                return true;
            }
            retryCount++;
        }

        try {
            SessionImpl.this.close();
        } catch (JMSException e) {
            if (logger.isDebugEnabled()) {
                logger.debug("Exception closing session", e);
            }
        }
        return false;
    }

    private boolean isSubscriberListEmpty(CopyOnWriteArrayList<? extends MessageConsumer> subscriberList) {
        if (subscriberList == null) {
            return true;
        }
        return !subscriberList.listIterator().hasNext();
    }

    private boolean shouldSkipBySelector(Subscription subscription, ReceivedMessage receivedMessage) {
        Node ast = subscriptions.getSelectorExpression(subscription);

        if (logger.isTraceEnabled()) {
            logger.trace("subscription : " + subscription + ", selector : " + ast);
        }

        if (ast == null) {
            return false;
        }

        final Boolean value = SelectorParser.evaluateSelector(ast, receivedMessage.msg);

        if (value == null) {
            if (logger.isDebugEnabled()) {
                logger.debug("Unable to evaluate selector ? ... ignoring message");
                logger.debug("Message : " + receivedMessage.msg);
            }
            receivedMessage.originalMessage.getAckRunnable().run();
            return true;
        }

        if (!Boolean.TRUE.equals(value)) {
            if (logger.isTraceEnabled()) {
                logger.trace("Selector DID NOT evaluate to true (" + value + "), ignore message");
            }
            receivedMessage.originalMessage.getAckRunnable().run();
            return true;
        }

        return false;
    }

    private void dispatchToSessionListener(MessageListener sessionMessageListener, ReceivedMessage receivedMessage) {
        if (logger.isTraceEnabled()) {
            logger.trace("Dispatching " + receivedMessage.originalMessage + " to session listener");
        }

        if (isMessageExpired(receivedMessage.originalMessage)) {
            receivedMessage.originalMessage.getAckRunnable().run();
            return;
        }

        try {
            final MessageImpl message = MessageUtil.createCloneForDispatch(
                    this,
                    receivedMessage.originalMessage,
                    receivedMessage.originalMessage.getSourceName(),
                    receivedMessage.originalMessage.getSubscriberId());

            deliverToListener(sessionMessageListener, receivedMessage, message, false);
        } catch (JMSException e) {
            if (logger.isDebugEnabled()) {
                logger.debug("Unexpected exception trying to process message");
                DebugUtil.dumpJMSStacktrace(logger, e);
            }
        }
    }

    private void dispatchToSubscribers(
            CopyOnWriteArrayList<? extends MessageConsumer> subscriberList,
            Subscription subscription,
            ReceivedMessage receivedMessage) {

        for (final MessageConsumer subscriber : subscriberList) {
            if (isClosed()) {
                break;
            }

            try {
                if (shouldAckAndSkipForNoLocal(subscriber, subscription, receivedMessage)) {
                    continue;
                }

                if (isMessageExpired(receivedMessage.originalMessage)) {
                    receivedMessage.originalMessage.getAckRunnable().run();
                    continue;
                }

                final MessageImpl message = MessageUtil.createCloneForDispatch(
                        this,
                        receivedMessage.originalMessage,
                        receivedMessage.originalMessage.getSourceName(),
                        receivedMessage.originalMessage.getSubscriberId());

                final MessageListener subscriberListener = subscriber.getMessageListener();

                if (logger.isTraceEnabled()) {
                    logger.trace("Dispatching " + message + " to subscriber listener? " + (subscriberListener != null));
                }

                if (subscriberListener != null) {
                    deliverToListener(subscriberListener, receivedMessage, message, false);
                } else {
                    sessionFacade.enqueueReceivedMessage(
                            subscriber,
                            new ReceivedMessage(receivedMessage.originalMessage, message, receivedMessage.destinationType),
                            false);
                }

                if (logger.isTraceEnabled()) {
                    logger.trace("Dispatching " + message + " DONE");
                }

            } catch (JMSException e) {
                if (logger.isDebugEnabled()) {
                    logger.debug("Unexpected exception trying to process message", e);
                }
            }
        }
    }

    private boolean shouldAckAndSkipForNoLocal(
            MessageConsumer subscriber,
            Subscription subscription,
            ReceivedMessage receivedMessage) throws JMSException {

        if (!getNoLocal(subscription, subscriber)) {
            return false;
        }

        if (!isLocallyPublished(receivedMessage.originalMessage.getJMSMessageID())) {
            return false;
        }

        receivedMessage.originalMessage.getAckRunnable().run();
        return true;
    }

    // ======= Placeholders for external members/methods =======
    // These exist only to make the sandbox parseable. Do NOT implement them here.

    private boolean handleRollbackInDispatch(List<TransactedReceiveOperation> ops) { return false; }
    private boolean isClosed() { return false; }
    private Subscription createSubscription(Object destinationType, String sourceName, String subscriberId) { return null; }
    private Object subscriptions;
    private boolean isMessageExpired(Object msg) { return false; }
    private void deliverToListener(MessageListener l, ReceivedMessage rm, MessageImpl m, boolean b) {}
    private boolean getNoLocal(Subscription s, MessageConsumer c) { return false; }
    private boolean isLocallyPublished(String id) { return false; }
    private Object sessionFacade;
    private Object logger;
    private static final int RETRY_DISPATCH_TO_TRANSACTION_ATTEMPTS = 3;

    private static class SessionImpl { void close() throws JMSException {} }
    private interface MessageConsumer { MessageListener getMessageListener() throws JMSException; }

