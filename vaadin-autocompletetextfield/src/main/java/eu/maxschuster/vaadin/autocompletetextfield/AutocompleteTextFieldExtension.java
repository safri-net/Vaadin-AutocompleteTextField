/*
 * Copyright 2015 Max Schuster.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package eu.maxschuster.vaadin.autocompletetextfield;

import com.vaadin.annotations.JavaScript;
import com.vaadin.annotations.StyleSheet;
import com.vaadin.server.AbstractJavaScriptExtension;
import com.vaadin.server.ClientConnector;
import com.vaadin.server.JsonCodec;
import com.vaadin.server.Resource;
import com.vaadin.ui.AbstractTextField;
import com.vaadin.ui.JavaScriptFunction;
import elemental.json.Json;
import elemental.json.JsonArray;
import elemental.json.JsonObject;
import elemental.json.JsonString;
import elemental.json.JsonValue;
import eu.maxschuster.vaadin.autocompletetextfield.shared.AutocompleteTextFieldExtensionState;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.logging.Logger;

/**
 * Extends an {@link AbstractTextField} with autocomplete (aka word completion)
 * functionality.
 * <p>
 * Uses a modified version of 
 * <a href="https://goodies.pixabay.com/javascript/auto-complete/demo.html">
 * autoComplete</a> originally developed by
 * <a href="https://pixabay.com/users/Simon/">Simon Steinberger</a>
 * 
 * @author Max Schuster
 * @see <a href="https://github.com/Pixabay/JavaScript-autoComplete">
 * https://github.com/Pixabay/JavaScript-autoComplete</a>
 * @see <a href="https://github.com/maxschuster/JavaScript-autoComplete">
 * https://github.com/maxschuster/JavaScript-autoComplete</a>
 */
@JavaScript({
    "vaadin://addons/autocompletetextfield/dist/AutocompleteTextFieldExtension.min.js"
})
@StyleSheet({
    "vaadin://addons/autocompletetextfield/dist/AutocompleteTextFieldExtension.css"
})
public class AutocompleteTextFieldExtension extends AbstractJavaScriptExtension {

    private static final long serialVersionUID = 1L;

    /**
     * The max amount of suggestions send to the client-side
     */
    private int suggestionLimit = 0;

    /**
     * The suggestion provider queried for suggesions
     */
    protected AutocompleteSuggestionProvider suggestionProvider = null;

    /**
     * Construct a new {@link AutocompleteTextFieldExtension} and extends the
     * given {@link AbstractTextField}.
     *
     * @param textField {@link AbstractTextField} to extend
     */
    public AutocompleteTextFieldExtension(AbstractTextField textField) {
        super(textField);
        addFunctions();
    }

    @Override
    public AbstractTextField getParent() {
        return (AbstractTextField) super.getParent();
    }

    @Override
    protected Class<? extends ClientConnector> getSupportedParentType() {
        return AbstractTextField.class;
    }

    @Override
    protected AutocompleteTextFieldExtensionState getState() {
        return (AutocompleteTextFieldExtensionState) super.getState();
    }

    @Override
    protected AutocompleteTextFieldExtensionState getState(boolean markAsDirty) {
        return (AutocompleteTextFieldExtensionState) super.getState(markAsDirty);
    }

    @Override
    public Class<? extends AutocompleteTextFieldExtensionState> getStateType() {
        return AutocompleteTextFieldExtensionState.class;
    }

    /**
     * Adds all {@link JavaScriptFunction}s
     */
    private void addFunctions() {
        
        /*
         * Receives a search term from the client-side, executes the query and
         * sends the results to the JavaScript method "setSuggestions".
         * 
         * @param requestId {JsonValue} Request id to send back to the client-side
         * @param term {String} The search term
         */
        addFunction("serverQuerySuggestions", new JavaScriptFunction() {

            private static final long serialVersionUID = 1L;

            @Override
            public void call(JsonArray arguments) {
                JsonValue requestId = arguments.get(0);
                String term = arguments.getString(1);
                Set<AutocompleteSuggestion> suggestions = querySuggestions(term);
                JsonValue suggestionsAsJson = suggestionsToJson(suggestions);
                callFunction("setSuggestions", requestId, suggestionsAsJson);
            }

        });
    }

    /**
     * Creates an {@link AutocompleteQuery} from the given search term and the
     * internal {@link #suggestionLimit} and executes it.
     *
     * Returns a {@link Set} of {@link AutocompleteSuggestion}s with a
     * predictable iteration order.
     *
     * @param term The search term.
     * @return Result {@link Set} of {@link AutocompleteSuggestion}s with a
     * predictable iteration order.
     */
    protected Set<AutocompleteSuggestion> querySuggestions(String term) {
        AutocompleteQuery autocompleteQuery = new AutocompleteQuery(this, term, suggestionLimit);
        return querySuggestions(autocompleteQuery);
    }

    /**
     * Executes the given {@link AutocompleteQuery} and makes sure the result is
     * within the boundries of the {@link AutocompleteQuery}'s limit.
     * <p>
     * Returns a {@link Set} of {@link AutocompleteSuggestion}s with a
     * predictable iteration order.
     *
     * @param query The Query.
     * @return Result {@link Set} of {@link AutocompleteSuggestion}s with a
     * predictable iteration order.
     */
    protected Set<AutocompleteSuggestion> querySuggestions(AutocompleteQuery query) {
        if (suggestionProvider == null) {
            // no suggestionProvider set
            return Collections.emptySet();
        }

        Collection<AutocompleteSuggestion> suggestions
                = suggestionProvider.querySuggestions(query);
        if (suggestions == null) {
            // suggestionProvider has returned null
            return Collections.emptySet();
        }

        int limit = query.getLimit();
        if (limit > 0 && limit < suggestions.size()) {
            // suggestionProvider has returned more results than allowed
            Set<AutocompleteSuggestion> subSet
                    = new LinkedHashSet<AutocompleteSuggestion>(limit);
            for (AutocompleteSuggestion suggestion : suggestions) {
                subSet.add(suggestion);
                if (subSet.size() >= limit) {
                    // size has reached the limit, ignore the following results
                    // TODO: Should we log a message here?
                    break;
                }
            }
            return subSet;
        } else {
            // suggestionProvider has respected the query limit
            return new LinkedHashSet<AutocompleteSuggestion>(suggestions);
        }
    }

    /**
     * Converts the given {@link AutocompleteSuggestion} into a
     * {@link JsonValue} representation
     * because {@link JsonCodec} can't handle it itself.
     *
     * @param suggestions Suggestions.
     * @return {@link JsonValue} representation.
     */
    protected JsonValue suggestionsToJson(Set<AutocompleteSuggestion> suggestions) {
        JsonArray array = Json.createArray();
        int i = 0;
        for (AutocompleteSuggestion suggestion : suggestions) {
            JsonObject object = Json.createObject();

            String value = suggestion.getValue();
            String description = suggestion.getDescription();
            Resource icon = suggestion.getIcon();
            List<String> styleNames = suggestion.getStyleNames();

            object.put("value", value != null
                    ? Json.create(value) : Json.createNull());
            object.put("description", description != null
                    ? Json.create(description) : Json.createNull());
            if (icon != null) {
                String key = "icon" + i;
                setResource(key, icon);
                object.put("icon", key);
            } else {
                object.put("icon", Json.createNull());
            }
            if (styleNames != null) {
                JsonArray styleNamesArray = Json.createArray();
                int s = 0;
                for (String styleName : styleNames) {
                    if (styleName == null) {
                        continue;
                    }
                    styleNamesArray.set(s++, styleName);
                }
                object.put("styleNames", styleNamesArray);
            } else {
                object.put("styleNames", Json.createNull());
            }

            array.set(i++, object);
        }
        return array;
    }

    /**
     * Gets a {@link Logger} instance for this class.
     *
     * @return {@link Logger} instance for this class.
     */
    private Logger getLogger() {
        return Logger.getLogger(AutocompleteTextFieldExtension.class.getName());
    }

    /**
     * Gets the active {@link AutocompleteSuggestionProvider}.
     *
     * @return The active {@link AutocompleteSuggestionProvider}.
     */
    public AutocompleteSuggestionProvider getSuggestionProvider() {
        return suggestionProvider;
    }

    /**
     * Sets the active {@link AutocompleteSuggestionProvider}.
     *
     * @param suggestionProvider The active
     * {@link AutocompleteSuggestionProvider}.
     */
    public void setSuggestionProvider(AutocompleteSuggestionProvider suggestionProvider) {
        this.suggestionProvider = suggestionProvider;
    }

    /**
     * Gets the maximum number of suggestions that are allowed.
     * <p>
     * If the active {@link AutocompleteSuggestionProvider} returns more
     * suggestions than allowed, the excess suggestions will be ignored!
     * <p>
     * If limit &lt;= 0 the suggestions won't be limited.
     *
     * @return Maximum number of suggestions.
     */
    public int getSuggestionLimit() {
        return suggestionLimit;
    }

    /**
     * Sets the maximum number of suggestions that are allowed.
     * <p>
     * If the active {@link AutocompleteSuggestionProvider} returns more
     * suggestions than allowed, the excess suggestions will be ignored!
     * <p>
     * If limit &lt;= 0 the suggestions won't be limited.
     *
     * @param suggestionLimit Maximum number of suggestions.
     */
    public void setSuggestionLimit(int suggestionLimit) {
        this.suggestionLimit = suggestionLimit;
    }

    /**
     * Checks whether items are rendered as HTML.
     * <p>
     * The default is false, i.e. to render that caption as plain text.
     *
     * @return true if the captions are rendered as HTML, false if rendered as
     * plain text.
     */
    public boolean isItemAsHtml() {
        return getState(false).itemAsHtml;
    }

    /**
     * Sets whether the items are rendered as HTML.
     * <p>
     * If set to true, the items are rendered in the browser as HTML and the
     * developer is responsible for ensuring no harmful HTML is used. If set to
     * false, the caption is rendered in the browser as plain text.
     * <p>
     * The default is false, i.e. to render that caption as plain text.
     *
     * @param itemAsHtml true if the items are rendered as HTML, false if
     * rendered as plain text.
     */
    public void setItemAsHtml(boolean itemAsHtml) {
        getState().itemAsHtml = itemAsHtml;
    }

    /**
     * Gets the minimum number of characters (&gt;=1) a user must type before a
     * search is performed.
     *
     * @return Minimum number of characters.
     */
    public int getMinChars() {
        return getState(false).minChars;
    }

    /**
     * Sets the minimum number of characters (&gt;=1) a user must type before a
     * search is performed.
     *
     * @param minChars Minimum number of characters.
     */
    public void setMinChars(int minChars) {
        getState().minChars = minChars;
    }

    /**
     * Gets the delay in milliseconds between when a keystroke occurs and when a
     * search is performed. A zero-delay is more responsive, but can produce a
     * lot of load.
     *
     * @return Search delay in milliseconds.
     */
    public int getDelay() {
        return getState(false).delay;
    }

    /**
     * Sets the delay in milliseconds between when a keystroke occurs and when a
     * search is performed. A zero-delay is more responsive, but can produce a
     * lot of load.
     *
     * @param delay Search delay in milliseconds.
     */
    public void setDelay(int delay) {
        getState().delay = delay;
    }

    /**
     * Checks if performed searches should be cached.
     *
     * @return Cache performed searches.
     */
    public boolean isCache() {
        return getState(false).cache;
    }

    /**
     * Sets if performed searches should be cached.
     *
     * @param cache Cache performed searches.
     */
    public void setCache(boolean cache) {
        getState().cache = cache;
    }
    
    public String getMenuStyleName() {
        List<String> styleNames = getState(false).menuStyleNames;
        String styleName = "";
        if (styleNames != null && !styleNames.isEmpty()) {
            Iterator<String> i = styleNames.iterator();
            while (i.hasNext()) {
                styleName += i.next();
                if (i.hasNext()) {
                    styleName += " ";
                }
            }
        }
        return styleName;
    }
    
    public void addMenuStyleName(String styleName) {
        List<String> styleNames = getState().menuStyleNames;
        if (styleName == null || styleName.isEmpty()) {
            return;
        }
        if (styleName.contains(" ")) {
            StringTokenizer tokenizer = new StringTokenizer(styleName, " ");
            while (tokenizer.hasMoreTokens()) {
                addMenuStyleName(tokenizer.nextToken());
            }
            return;
        }
        if (styleNames == null) {
            styleNames = new ArrayList<String>();
            getState().menuStyleNames = styleNames;
        }
        styleNames.add(styleName);
    }
    
    public void removeMenuStyleName(String styleName) {
        List<String> styleNames = getState().menuStyleNames;
        if (styleName == null || styleName.isEmpty() || styleNames == null) {
            return;
        }
        if (styleName.contains(" ")) {
            StringTokenizer tokenizer = new StringTokenizer(styleName, " ");
            while (tokenizer.hasMoreTokens()) {
                styleNames.remove(tokenizer.nextToken());
            }
        } else {
            styleNames.remove(styleName);
        }
    }

}