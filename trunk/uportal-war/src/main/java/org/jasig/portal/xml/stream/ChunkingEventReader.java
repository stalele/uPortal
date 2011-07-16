/**
 * Licensed to Jasig under one or more contributor license
 * agreements. See the NOTICE file distributed with this work
 * for additional information regarding copyright ownership.
 * Jasig licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a
 * copy of the License at:
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.jasig.portal.xml.stream;

import java.io.StringWriter;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.servlet.http.HttpServletRequest;
import javax.xml.namespace.QName;
import javax.xml.stream.XMLEventFactory;
import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLEventWriter;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.events.StartElement;
import javax.xml.stream.events.XMLEvent;

import org.jasig.portal.character.stream.CharacterEventSource;
import org.jasig.portal.character.stream.events.CharacterDataEvent;
import org.jasig.portal.character.stream.events.CharacterDataEventImpl;
import org.jasig.portal.character.stream.events.CharacterEvent;
import org.jasig.portal.character.stream.events.CharacterEventTypes;

/**
 * Used with code that serializes StAX events into a string. Watches for specific XML tags in a StAX
 * stream and chunks the string data at each XML tag occurrence.
 * 
 * @author Eric Dalquist
 * @version $Revision$
 */
public class ChunkingEventReader extends BaseXMLEventReader {
    private static final XMLEventFactory EVENT_FACTORY = XMLEventFactory.newFactory();
    
    private final List<CharacterEvent> characterEvents = new LinkedList<CharacterEvent>();

    private final HttpServletRequest request;
    private final Map<String, CharacterEventSource> chunkingElements;
    private final Map<Pattern, CharacterEventSource> chunkingPatterns;
    private final XMLEventWriter xmlEventWriter;
    private final StringWriter writer;
    private boolean removeXmlDeclaration = true;
    
    //Declare this at the class level to reduce excess object creation
    private final StringBuffer characterChunkingBuffer = new StringBuffer();
    
    //to handle peek() calls
    private XMLEvent peekedEvent = null;
    
    //Handle chunking immediately after a StartElement
    private StartElement captureEvent = null;

    public ChunkingEventReader(HttpServletRequest request,
            Map<String, CharacterEventSource> chunkingElements,
            Map<Pattern, CharacterEventSource> chunkingPatterns, 
            XMLEventReader xmlEventReader, XMLEventWriter xmlEventWriter,
            StringWriter writer) {
        super(xmlEventReader);

        this.request = request;
        this.chunkingElements = chunkingElements;
        this.chunkingPatterns = chunkingPatterns;
        this.xmlEventWriter = xmlEventWriter;
        this.writer = writer;
    }

    public boolean isRemoveXmlDeclaration() {
        return this.removeXmlDeclaration;
    }
    /**
     * Remove the XML declaration from the output. Defaults to true
     */
    public void setRemoveXmlDeclaration(boolean removeXmlDeclaration) {
        this.removeXmlDeclaration = removeXmlDeclaration;
    }

    /**
     * @return The character events generated by the reader
     */
    public List<CharacterEvent> getCharacterEvents() {
        return this.characterEvents;
    }
    
    @Override
    public void remove() {
        throw new UnsupportedOperationException();
    }

    @Override
    public XMLEvent peek() throws XMLStreamException {
        if (this.peekedEvent == null) {
            this.peekedEvent = this.nextEvent();
        }
        
        return this.peekedEvent;
    }

    @Override
    protected XMLEvent internalNextEvent() throws XMLStreamException {
        XMLEvent event = null;
        
        //Read from the buffer if there was a peek
        if (this.peekedEvent != null) {
            event = this.peekedEvent;
            this.peekedEvent = null;
            return event;
        }
        
        final XMLEvent previousEvent = this.getPreviousEvent();
        if (previousEvent != null && previousEvent.isStartDocument()) {
            this.xmlEventWriter.flush();
            this.clearWriter();
        }
        
        if (this.captureEvent != null) {
            event = this.tryChunking(this.captureEvent);
            this.captureEvent = null;
        }
        else {
            event = this.getParent().nextEvent();
            if (event.isStartElement()) {
                final StartElement startElement = event.asStartElement();
                event = this.tryChunking(startElement);
            }
        }
        
        return event;
    }

    private XMLEvent tryChunking(StartElement startElement) throws XMLStreamException {
        QName elementName = startElement.getName();
        CharacterEventSource characterEventSource = this.chunkingElements.get(elementName.getLocalPart());
        while (characterEventSource != null) {
            final XMLEvent previousEvent = this.getPreviousEvent();
            if (previousEvent != null && previousEvent.isStartElement()) {
                this.captureEvent = startElement;
                
                //Write an empty Character event to force the serializer to finish writing the previous StartElement
                //It is left open since ATTRIBUTE events can follow a START_ELEMENT event.
                return EVENT_FACTORY.createCharacters("");
            }
            
            //Capture the characters written out to this point then clear the buffer
            this.captureCharacterDataEvent();
            
            //Get the generated events for the element
            final XMLEventReader parent = this.getParent();
            final List<CharacterEvent> generatedCharacterEvents = characterEventSource.getCharacterEvents(this.request, parent, startElement);
            if (generatedCharacterEvents != null) {
                this.characterEvents.addAll(generatedCharacterEvents);
            }
            
            //Read the next event off the reader
            final XMLEvent nextEvent = parent.nextEvent();
            if (nextEvent.isStartElement()) {
                startElement = nextEvent.asStartElement();
                elementName = startElement.getName(); 
                characterEventSource = this.chunkingElements.get(elementName.getLocalPart());
            }
            else {
                return nextEvent;
            }
        }
        
        return startElement;
    }

    protected void captureCharacterDataEvent() throws XMLStreamException {
        this.xmlEventWriter.flush();
        
        //Add character chunk to events
        final String chunk = this.writer.toString();
        
        final List<CharacterEvent> characterEvents = this.chunkString(chunk);
        
        //Add all the characterEvents
        this.characterEvents.addAll(characterEvents);
        
        this.clearWriter();
    }

    /**
     * Delete all data in the Writer
     */
    private void clearWriter() {
        final StringBuffer buffer = this.writer.getBuffer();
        buffer.delete(0, buffer.length());
    }

    /**
     * Breaks up the String into a List of CharacterEvents based on the configured Map of Patterns to 
     * CharacterEventSources
     */
    protected List<CharacterEvent> chunkString(final String chunk) {
        final List<CharacterEvent> characterEvents = new LinkedList<CharacterEvent>();
        characterEvents.add(new CharacterDataEventImpl(chunk));

        //Iterate over the chunking patterns
        for (final Map.Entry<Pattern, CharacterEventSource> chunkingPatternEntry : this.chunkingPatterns.entrySet()) {
            final Pattern pattern = chunkingPatternEntry.getKey();

            //Iterate over the events that have been chunked so far
            for (final ListIterator<CharacterEvent> characterDataEventItr = characterEvents.listIterator(); characterDataEventItr.hasNext(); ) {
                final CharacterEvent characterDataEvent = characterDataEventItr.next();
                
                //If it is a character event it may need further chunking
                if (CharacterEventTypes.CHARACTER == characterDataEvent.getEventType()) {
                    final String data = ((CharacterDataEvent)characterDataEvent).getData();
                    
                    final Matcher matcher = pattern.matcher(data);
                    if (matcher.find()) {
                        //Found a match, replacing the CharacterDataEvent that was found
                        characterDataEventItr.remove();

                        do {
                            //Add all of the text up to the match as a new chunk
                            characterChunkingBuffer.delete(0, characterChunkingBuffer.length());
                            matcher.appendReplacement(characterChunkingBuffer, "");
                            characterDataEventItr.add(new CharacterDataEventImpl(characterChunkingBuffer.toString()));

                            //Get the generated CharacterEvents for the match
                            final CharacterEventSource eventSource = chunkingPatternEntry.getValue();
                            final MatchResult matchResult = matcher.toMatchResult();
                            final List<CharacterEvent> generatedCharacterEvents = eventSource.getCharacterEvents(this.request, matchResult);
                            
                            //Add the generated CharacterEvents to the list
                            for (final CharacterEvent generatedCharacterEvent : generatedCharacterEvents) {
                                characterDataEventItr.add(generatedCharacterEvent);
                            }
                        } while (matcher.find());
                            
                        //Add any remaining text from the original CharacterDataEvent
                        characterChunkingBuffer.delete(0, characterChunkingBuffer.length());
                        matcher.appendTail(characterChunkingBuffer);
                        characterDataEventItr.add(new CharacterDataEventImpl(characterChunkingBuffer.toString()));
                    }
                }
            }
        }
        return characterEvents;
    }

    @Override
    public boolean hasNext() {
        if (this.peekedEvent != null) {
            return true;
        }
        
        if (!this.getParent().hasNext()) {
            return false;
        }

        try {
            this.peekedEvent = this.nextEvent();
        }
        catch (NoSuchElementException e) {
            return false;
        }
        catch (XMLStreamException e) {
            throw new RuntimeException(e);
        }
        
        return true;
    }

    @Override
    public void close() throws XMLStreamException {
        captureCharacterDataEvent();
        this.getParent().close();
    }
}