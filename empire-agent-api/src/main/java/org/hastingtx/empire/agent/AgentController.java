package org.hastingtx.empire.agent;

/**
 * A country's brain. Given what that country can see, return what it wants to do. The
 * implementation never touches the World; it only sees a CountryView and only speaks in
 * Commands, which are validated exactly as a human's would be.
 */
public interface AgentController {
    TurnResult turn(TurnContext context);
}
