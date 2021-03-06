/*
 * Copyright (c) 2002-2020 "Neo4j,"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.configuration;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

import org.neo4j.configuration.helpers.DurationRange;
import org.neo4j.configuration.helpers.SocketAddress;
import org.neo4j.graphdb.config.Configuration;
import org.neo4j.graphdb.config.Setting;
import org.neo4j.internal.helpers.HostnamePort;
import org.neo4j.string.SecureString;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;
import static org.neo4j.configuration.SettingConstraints.PORT;
import static org.neo4j.configuration.SettingConstraints.POWER_OF_2;
import static org.neo4j.configuration.SettingConstraints.any;
import static org.neo4j.configuration.SettingConstraints.dependency;
import static org.neo4j.configuration.SettingConstraints.except;
import static org.neo4j.configuration.SettingConstraints.is;
import static org.neo4j.configuration.SettingConstraints.matches;
import static org.neo4j.configuration.SettingConstraints.max;
import static org.neo4j.configuration.SettingConstraints.min;
import static org.neo4j.configuration.SettingConstraints.range;
import static org.neo4j.configuration.SettingValueParsers.BOOL;
import static org.neo4j.configuration.SettingValueParsers.BYTES;
import static org.neo4j.configuration.SettingValueParsers.DOUBLE;
import static org.neo4j.configuration.SettingValueParsers.DURATION;
import static org.neo4j.configuration.SettingValueParsers.DURATION_RANGE;
import static org.neo4j.configuration.SettingValueParsers.FALSE;
import static org.neo4j.configuration.SettingValueParsers.HOSTNAME_PORT;
import static org.neo4j.configuration.SettingValueParsers.INT;
import static org.neo4j.configuration.SettingValueParsers.LONG;
import static org.neo4j.configuration.SettingValueParsers.NORMALIZED_RELATIVE_URI;
import static org.neo4j.configuration.SettingValueParsers.PATH;
import static org.neo4j.configuration.SettingValueParsers.SECURE_STRING;
import static org.neo4j.configuration.SettingValueParsers.SOCKET_ADDRESS;
import static org.neo4j.configuration.SettingValueParsers.STRING;
import static org.neo4j.configuration.SettingValueParsers.TIMEZONE;
import static org.neo4j.configuration.SettingValueParsers.TRUE;
import static org.neo4j.configuration.SettingValueParsers.listOf;
import static org.neo4j.configuration.SettingValueParsers.ofEnum;
import static org.neo4j.configuration.SettingValueParsers.ofPartialEnum;
import static org.neo4j.graphdb.config.Configuration.EMPTY;

class SettingTest
{
    @Test
    void testSuffix()
    {
        var setting1 = (SettingImpl<Integer>) setting( "setting", INT );
        assertEquals( "setting", setting1.suffix() );
        var setting2 = (SettingImpl<Integer>) setting( "setting.suffix", INT );
        assertEquals( "suffix", setting2.suffix() );
        var setting3 = (SettingImpl<Integer>) setting( "", INT );
        assertEquals( "", setting3.suffix() );
        var setting4 = (SettingImpl<Integer>) setting( null, INT );
        assertNull( setting4.suffix() );
    }

    @Test
    void testInteger()
    {
        var setting = (SettingImpl<Integer>) setting( "setting", INT );
        assertEquals( 5, setting.parse( "5" ) );
        assertEquals( -76, setting.parse( "-76" ) );
        assertThrows( IllegalArgumentException.class, () -> setting.parse( "foo" ) );
    }

    @Test
    void testLong()
    {
        var setting = (SettingImpl<Long>) setting( "setting", LONG );
        assertEquals( 112233445566778899L, setting.parse( "112233445566778899" ) );
        assertEquals( -112233445566778899L, setting.parse( "-112233445566778899" ) );
        assertThrows( IllegalArgumentException.class, () -> setting.parse( "foo" ) );
    }

    @Test
    void testString()
    {
        var setting = (SettingImpl<String>) setting( "setting", STRING );
        assertEquals( "foo", setting.parse( "foo" ) );
        assertEquals( "bar", setting.parse( "  bar   " ) );
    }

    @Test
    void testSecureString()
    {
        var setting = (SettingImpl<SecureString>) setting( "setting", SECURE_STRING );
        assertEquals( "foo", setting.parse( "foo" ).getString() );
        assertNotEquals( "foo", setting.parse( "foo" ).toString() );
        assertEquals( "bar", setting.parse( "  bar   " ).getString() );
        assertNotEquals( "foo", setting.valueToString( setting.parse( "foo" ) ) );
    }

    @Test
    void testDouble()
    {
        BiFunction<Double,Double,Boolean> compareDoubles = ( Double d1, Double d2 ) -> Math.abs( d1 - d2 ) < 0.000001;

        var setting = (SettingImpl<Double>) setting( "setting", DOUBLE );
        assertEquals( 5.0, setting.parse( "5" ) );
        assertTrue( compareDoubles.apply( -.123, setting.parse( "-0.123" ) ) );
        assertTrue( compareDoubles.apply( 5.0, setting.parse( "5" ) ) );
        assertThrows( IllegalArgumentException.class, () -> setting.parse( "foo" ) );
    }

    @Test
    void testList()
    {
        var setting = (SettingImpl<List<Integer>>) setting( "setting", listOf( INT ) );
        assertEquals( 5, setting.parse( "5" ).get( 0 ) );
        assertEquals( 0, setting.parse( "" ).size() );
        assertEquals( 4, setting.parse( "5, 31, -4  ,2" ).size() );
        assertEquals( Arrays.asList( 4, 2, 3, 1 ), setting.parse( "4,2,3,1" ) );
        assertThrows( IllegalArgumentException.class, () -> setting.parse( "2,3,foo,7" ) );

        assertFalse( setting.valueToString( setting.parse( "4,2,3,1" ) ).startsWith( "[" ) );
        assertFalse( setting.valueToString( setting.parse( "4,2,3,1" ) ).endsWith( "]" ) );
    }

    @Test
    void testEnum()
    {
        var setting = (SettingImpl<Colors>) setting( "setting", ofEnum( Colors.class ) );
        assertEquals( Colors.BLUE, setting.parse( "BLUE" ) );
        assertEquals( Colors.GREEN, setting.parse( "gReEn" ) );
        assertEquals( Colors.RED, setting.parse( "red" ));
        assertThrows( IllegalArgumentException.class, () -> setting.parse( "orange" ) );
    }

    @Test
    void testPartialEnum()
    {
        var setting = (SettingImpl<Colors>) setting( "setting", ofPartialEnum( Colors.GREEN, Colors.BLUE ) );
        assertEquals( Colors.BLUE, setting.parse( "BLUE" ) );
        assertEquals( Colors.GREEN, setting.parse( "gReEn" ) );
        assertThrows( IllegalArgumentException.class, () -> setting.parse( "red" ) );
    }

    @Test
    void testStringEnum()
    {
        var setting = (SettingImpl<StringEnum>) setting( "setting", ofEnum( StringEnum.class ) );
        assertEquals( StringEnum.DEFAULT, setting.parse( "default" ) );
        assertEquals( StringEnum.V_1, setting.parse( "1.0" ) );
        assertEquals( StringEnum.V_1_1, setting.parse( "1.1" ) );
        assertThrows( IllegalArgumentException.class, () -> setting.parse( "orange" ) );

    }

    @Test
    void testBool()
    {
        var setting = (SettingImpl<Boolean>) setting( "setting", BOOL );
        assertTrue( setting.parse( "True" ) );
        assertFalse( setting.parse( "false" ) );
        assertFalse( setting.parse( "false" ) );
        assertFalse( setting.parse( FALSE ) );
        assertTrue( setting.parse( TRUE ) );
        assertThrows( IllegalArgumentException.class, () -> setting.parse( "foo" ) );
    }

    @Test
    void testDuration()
    {
        var setting = (SettingImpl<Duration>) setting( "setting", DURATION );
        assertEquals( 60, setting.parse( "1m" ).toSeconds() );
        assertEquals( 1000, setting.parse( "1s" ).toMillis() );
        assertThrows( IllegalArgumentException.class, () -> setting.parse( "foo" ) );

        assertEquals( "1s", setting.valueToString( setting.parse( "1s" ) ) );
        assertEquals( "3m", setting.valueToString( setting.parse( "3m" ) ) );

        // Anything less than a millisecond is rounded down
        assertEquals( "0ns", setting.valueToString( setting.parse( "0s" ) ) );
        assertEquals( "0ns", setting.valueToString( setting.parse( "1ns" ) ) );
        assertEquals( "0ns", setting.valueToString( setting.parse( "999999ns" ) ) );
        assertEquals( "0ns", setting.valueToString( setting.parse( "999μs" ) ) );

        // Time strings containing multiple units are permitted
        assertEquals( "11d19h25m4s50ms", setting.valueToString( setting.parse( "11d19h25m4s50ms607μs80ns" ) ) );
        // Weird time strings will be converted to something more readable
        assertEquals( "2m1ms", setting.valueToString( setting.parse( "1m60000ms1000000ns" ) ) );
    }

    @Test
    void testDurationRange()
    {
        var setting = (SettingImpl<DurationRange>) setting( "setting", DURATION_RANGE );
        assertEquals( 60, setting.parse( "1m-2m" ).getMin().toSeconds() );
        assertEquals( 120, setting.parse( "1m-2m" ).getMax().toSeconds() );
        assertEquals( 1000, setting.parse( "1s-2s" ).getMin().toMillis() );
        assertEquals( 2000, setting.parse( "1s-2s" ).getMax().toMillis() );
        assertThrows( IllegalArgumentException.class, () -> setting.parse( "1s" ) );
        assertThrows( IllegalArgumentException.class, () -> setting.parse( "1s-" ) );
        assertThrows( IllegalArgumentException.class, () -> setting.parse( "-1s" ) );
        assertThrows( IllegalArgumentException.class, () -> setting.parse( "-1s--2s" ) );
        assertThrows( IllegalArgumentException.class, () -> setting.parse( "2s-1s" ) );
        assertThrows( IllegalArgumentException.class, () -> setting.parse( "2000ms-1s" ) );

        // DurationRange may have zero delta
        assertEquals( 1, setting.parse( "1s-1s" ).getMin().toSeconds() );
        assertEquals( 1, setting.parse( "1s-1s" ).getMax().toSeconds() );
        assertEquals( 0, setting.parse( "1s-1s" ).getDelta().toNanos() );

        assertEquals( "0ns-0ns", setting.valueToString( setting.parse( "0s-0s" ) ) );
        assertEquals( "1s-2s", setting.valueToString( setting.parse( "1s-2s" ) ) );
        assertEquals( "3m-6m", setting.valueToString( setting.parse( "[3m-6m]" ) ) );

        // Time strings containing multiple units are permitted
        assertEquals( "0ns-1m23s456ms", setting.valueToString( setting.parse( "0s-1m23s456ms" ) ) );

        // Units will be converted to something "more readable"
        assertEquals( "1s-2s500ms", setting.valueToString( setting.parse( "1000ms-2500ms" ) ) );

        // Anything less than a millisecond is rounded down
        assertEquals( "0ns-0ns", setting.valueToString( setting.parse( "999μs-999999ns" ) ) );
        assertEquals( 0, setting.parse( "999μs-999999ns" ).getDelta().toNanos() );
    }

    @Test
    void testHostnamePort()
    {
        var setting = (SettingImpl<HostnamePort>) setting( "setting", HOSTNAME_PORT );
        assertEquals( new HostnamePort( "localhost", 7474 ), setting.parse( "localhost:7474" ) );
        assertEquals( new HostnamePort( "localhost", 1000, 2000 ), setting.parse( "localhost:1000-2000" ) );
        assertEquals( new HostnamePort( "localhost" ), setting.parse( "localhost" ) );
        assertThrows( IllegalArgumentException.class, () -> setting.parse( "localhost:5641:7474" ) );
        assertThrows( IllegalArgumentException.class, () -> setting.parse( "localhost:foo" ) );
        assertThrows( IllegalArgumentException.class, () -> setting.parse( "7474:localhost" ) );
    }

    @Test
    void testTimeZone()
    {
        var setting = (SettingImpl<ZoneId>) setting( "setting", TIMEZONE );
        assertEquals( ZoneId.from( ZoneOffset.UTC ), setting.parse( "+00:00" ) );
        assertThrows( IllegalArgumentException.class, () -> setting.parse( "foo" ) );
    }

    @Test
    void testSocket()
    {
        var setting = (SettingImpl<SocketAddress>) setting( "setting", SOCKET_ADDRESS );
        assertEquals( new SocketAddress( "127.0.0.1", 7474 ), setting.parse( "127.0.0.1:7474" ) );
        assertEquals( new SocketAddress( "127.0.0.1", -1 ), setting.parse( "127.0.0.1" ) );
        assertEquals( new SocketAddress( null, 7474 ), setting.parse( ":7474" ) );
    }

    @Test
    void testSocketSolve()
    {
        var setting = (SettingImpl<SocketAddress>) setting( "setting", SOCKET_ADDRESS );
        assertEquals( new SocketAddress( "localhost", 7473 ),setting.solveDependency( setting.parse( "localhost:7473" ),  setting.parse( "127.0.0.1:7474" ) ) );
        assertEquals( new SocketAddress( "127.0.0.1", 7473 ),setting.solveDependency( setting.parse( ":7473" ),  setting.parse( "127.0.0.1:7474" ) ) );
        assertEquals( new SocketAddress( "127.0.0.1", 7473 ),setting.solveDependency( setting.parse( ":7473" ),  setting.parse( "127.0.0.1" ) ) );
        assertEquals( new SocketAddress( "localhost", 7474 ),setting.solveDependency( setting.parse( "localhost" ),  setting.parse( ":7474" ) ) );
        assertEquals( new SocketAddress( "localhost", 7474 ),setting.solveDependency( setting.parse( "localhost" ),  setting.parse( "127.0.0.1:7474" ) ) );
        assertEquals( new SocketAddress( "localhost", 7474 ),setting.solveDependency( null,  setting.parse( "localhost:7474" ) ) );
    }

    @Test
    void testBytes()
    {
        var setting = (SettingImpl<Long>) setting( "setting", BYTES );
        assertEquals( 2048, setting.parse( "2k" ) );
        assertThrows( IllegalArgumentException.class, () -> setting.parse( "1gig" ) );
        assertThrows( IllegalArgumentException.class, () -> setting.parse( "-1M" ) );
    }

    @Test
    void testURI()
    {
        var setting = (SettingImpl<URI>) setting( "setting", SettingValueParsers.URI );
        assertEquals( URI.create( "/path/to/../something/" ), setting.parse( "/path/to/../something/" ) );
    }

    @Test
    void testNormalizedRelativeURI()
    {
        var setting = (SettingImpl<URI>) setting( "setting", NORMALIZED_RELATIVE_URI );
        assertEquals( URI.create( "/path/to/something" ), setting.parse( "/path/away/from/../../to/something/" ) );
    }

    @Test
    void testPath()
    {
        var setting = (SettingImpl<Path>) setting( "setting", PATH );
        assertEquals( Path.of( "/absolute/path" ), setting.parse( "/absolute/path" ) );
        assertEquals( Path.of( "/absolute/path" ), setting.parse( "/absolute/wrong/../path" ) );
    }

    @Test
    void testSolvePath()
    {
        var setting = (SettingImpl<Path>) setting( "setting", PATH );
        assertEquals( Path.of( "/base/path/to/file" ).toAbsolutePath(),
                setting.solveDependency( setting.parse( "to/file" ), setting.parse( "/base/path" ).toAbsolutePath() ) );
        assertEquals( Path.of( "/to/file" ).toAbsolutePath(),
                setting.solveDependency( setting.parse( "/to/file" ), setting.parse( "/base/path" ).toAbsolutePath() ) );
        assertEquals( Path.of( "/base/path/" ).toAbsolutePath(),
                setting.solveDependency( setting.parse( "" ), setting.parse( "/base/path/" ).toAbsolutePath() ) );
        assertEquals( Path.of( "/base/path" ).toAbsolutePath(), setting.solveDependency( setting.parse( "path" ), setting.parse( "/base" ).toAbsolutePath() ) );
        assertEquals( Path.of( "/base" ).toAbsolutePath(), setting.solveDependency( null, setting.parse( "/base" ).toAbsolutePath() ) );
        assertThrows( IllegalArgumentException.class, () -> setting.solveDependency( setting.parse( "path" ), setting.parse( "base" ) ) );
    }

    @Test
    void testDefaultSolve()
    {
        var defaultSolver = new SettingValueParser<String>()
        {
            @Override
            public String parse( String value )
            {
                return value;
            }

            @Override
            public String getDescription()
            {
                return "default solver";
            }

            @Override
            public Class<String> getType()
            {
                return String.class;
            }
        };

        var setting = (SettingImpl<String>) setting( "setting", defaultSolver );
        assertEquals( "foo", setting.solveDependency( "foo", "bar" ) );
        assertEquals( "bar", setting.solveDependency( null, "bar" ) );
        assertEquals( "foo", setting.solveDependency( "foo", null ) );
        assertNull( setting.solveDependency( null, null ) );
    }

    @Test
    void testMinConstraint()
    {
        var setting = (SettingImpl<Integer>) settingBuilder( "setting", INT ).addConstraint( min( 10 ) ).build();
        assertDoesNotThrow( () -> setting.validate( 100, EMPTY ) );
        assertDoesNotThrow( () -> setting.validate( 10, EMPTY ) );
        assertThrows( IllegalArgumentException.class, () -> setting.validate( 9, EMPTY ) );
    }

    @Test
    void testMaxConstraint()
    {
        var setting = (SettingImpl<Integer>) settingBuilder( "setting", INT ).addConstraint( max( 10 ) ).build();
        assertDoesNotThrow( () -> setting.validate( -100, EMPTY ) );
        assertDoesNotThrow( () -> setting.validate( 10, EMPTY ) );
        assertThrows( IllegalArgumentException.class, () -> setting.validate( 11, EMPTY ) );
    }

    @Test
    void testRangeConstraint()
    {
        var setting = (SettingImpl<Double>) settingBuilder( "setting", DOUBLE ).addConstraint( range( 10.0, 20.0 ) ).build();

        assertThrows( IllegalArgumentException.class, () -> setting.validate( 9.9, EMPTY ) );
        assertThrows( IllegalArgumentException.class, () -> setting.validate( 20.01, EMPTY ) );
        assertDoesNotThrow( () -> setting.validate( 10.1, EMPTY ) );
        assertDoesNotThrow( () -> setting.validate( 19.9999, EMPTY ) );
    }

    @Test
    void testExceptConstraint()
    {
        var setting = (SettingImpl<String>) settingBuilder( "setting", STRING ).addConstraint( except( "foo" ) ).build();
        assertThrows( IllegalArgumentException.class, () -> setting.validate( "foo", EMPTY ) );
        assertDoesNotThrow( () -> setting.validate( "bar", EMPTY ) );
    }

    @Test
    void testMatchesConstraint()
    {
        var setting = (SettingImpl<String>) settingBuilder( "setting", STRING ).addConstraint( matches( "^[^.]+\\.[^.]+$" ) ).build();
        assertDoesNotThrow( () -> setting.validate( "foo.bar", EMPTY ) );
        assertThrows( IllegalArgumentException.class, () -> setting.validate( "foo", EMPTY ) );
    }

    @Test
    void testPowerOf2Constraint()
    {
        var setting = (SettingImpl<Long>) settingBuilder( "setting", LONG ).addConstraint( POWER_OF_2 ).build();
        assertDoesNotThrow( () -> setting.validate( 8L, EMPTY ) );
        assertDoesNotThrow( () -> setting.validate( 4294967296L, EMPTY ) );
        assertThrows( IllegalArgumentException.class, () -> setting.validate( 1023L, EMPTY ) );
    }

    @Test
    void testPortConstraint()
    {
        var setting = (SettingImpl<Integer>) settingBuilder( "setting", INT ).addConstraint( PORT ).build();
        assertDoesNotThrow( () -> setting.validate( 7474, EMPTY ) );
        assertThrows( IllegalArgumentException.class, () -> setting.validate( 200000, EMPTY ) );
        assertThrows( IllegalArgumentException.class, () -> setting.validate( -1, EMPTY ) );
    }

    @Test
    void testIsConstraint()
    {
        var setting = (SettingImpl<Integer>) settingBuilder( "setting", INT ).addConstraint( is( 10 ) ).build();
        assertDoesNotThrow( () -> setting.validate( 10, EMPTY ) );
        assertThrows( IllegalArgumentException.class, () -> setting.validate( 9, EMPTY ) );
    }

    @Test
    void testAnyConstraint()
    {
        var intSetting = (SettingImpl<Integer>) settingBuilder( "setting", INT )
                .addConstraint( any( min( 30 ), is( 0 ), is( -10 ) )  ).build();
        assertDoesNotThrow( () -> intSetting.validate( 30, EMPTY ) );
        assertDoesNotThrow( () -> intSetting.validate( 100, EMPTY ) );
        assertDoesNotThrow( () -> intSetting.validate( 0, EMPTY ) );
        assertDoesNotThrow( () -> intSetting.validate( -10, EMPTY ) );
        assertThrows( IllegalArgumentException.class, () -> intSetting.validate( 29, EMPTY ) );
        assertThrows( IllegalArgumentException.class, () -> intSetting.validate( 1, EMPTY ) );
        assertThrows( IllegalArgumentException.class, () -> intSetting.validate( -9, EMPTY ) );

        var durationSetting = (SettingImpl<Duration>) settingBuilder( "setting", DURATION )
                .addConstraint( any( min( Duration.ofMinutes( 30 ) ), is( Duration.ZERO ) )  ).build();
        assertDoesNotThrow( () -> durationSetting.validate( Duration.ofMinutes( 30 ), EMPTY ) );
        assertDoesNotThrow( () -> durationSetting.validate( Duration.ofHours( 1 ), EMPTY ) );
        assertDoesNotThrow( () -> durationSetting.validate( Duration.ZERO, EMPTY ) );
        assertThrows( IllegalArgumentException.class, () -> durationSetting.validate( Duration.ofMinutes( 29 ), EMPTY ) );
        assertThrows( IllegalArgumentException.class, () -> durationSetting.validate( Duration.ofMillis( 1 ), EMPTY ) );

    }

    @Test
    void testDependencyConstraint()
    {
        //Given
        var intSetting = (SettingImpl<Integer>) settingBuilder( "int-setting", INT ).build();
        var enumSetting = (SettingImpl<Colors>) settingBuilder( "enum-setting", ofEnum( Colors.class ) ).build();
        Map<Setting<?>,Object> settings = new HashMap<>();

        Configuration simpleConfig = new Configuration()
        {
            @Override
            public <T> T get( Setting<T> setting )
            {
                return (T) settings.get( setting );
            }
        };
        var dependingIntSetting = (SettingImpl<Integer>) settingBuilder( "setting", INT )
                .addConstraint( dependency( max( 3 ), max(7), intSetting, min( 3 ) ) ).build();

        var dependingEnumSetting = (SettingImpl<List<String>>) settingBuilder( "setting", listOf( STRING ) )
                .addConstraint( dependency( SettingConstraints.size( 2 ), SettingConstraints.size( 4 ) , enumSetting, is( Colors.BLUE ) ) ).build();

        //When
        settings.put( intSetting, 5 );
        settings.put( enumSetting, Colors.BLUE );
        //Then
        assertDoesNotThrow( () -> dependingIntSetting.validate( 3, simpleConfig ) );
        assertThrows( IllegalArgumentException.class, () -> dependingIntSetting.validate( 4, simpleConfig ) );

        assertDoesNotThrow( () -> dependingEnumSetting.validate( List.of( "a", "b" ), simpleConfig ) );
        assertThrows( IllegalArgumentException.class, () -> dependingEnumSetting.validate( List.of( "a", "b", "c" ), simpleConfig ) );
        assertThrows( IllegalArgumentException.class, () -> dependingEnumSetting.validate( List.of( "a", "b", "c", "d" ), simpleConfig ) );

        //When
        settings.put( intSetting, 2 );
        settings.put( enumSetting, Colors.GREEN );
        //Then
        assertDoesNotThrow( () -> dependingIntSetting.validate( 4, simpleConfig ) );
        assertThrows( IllegalArgumentException.class, () -> dependingIntSetting.validate( 8, simpleConfig ) );

        assertDoesNotThrow( () -> dependingEnumSetting.validate( List.of( "a", "b", "c", "d" ), simpleConfig ) );
        assertThrows( IllegalArgumentException.class, () -> dependingEnumSetting.validate( List.of( "a", "b" ), simpleConfig ) );
        assertThrows( IllegalArgumentException.class, () -> dependingEnumSetting.validate( List.of( "a", "b", "c" ), simpleConfig ) );

    }

    @Test
    void testDescriptionWithConstraints()
    {
        //Given
        var oneConstraintSetting = (SettingImpl<Long>) settingBuilder( "setting.name", LONG )
                .addConstraint( POWER_OF_2 )
                .build();

        var twoConstraintSetting = (SettingImpl<Integer>) settingBuilder( "setting.name", INT )
                .addConstraint( min( 2 ) )
                .addConstraint( max( 10 ) )
                .build();

        var enumSetting = (SettingImpl<Colors>) settingBuilder( "setting.name", ofEnum( Colors.class ) ).build();
        var intSetting = (SettingImpl<Integer>) settingBuilder( "setting.name", INT ).build();

        var dependencySetting1 = (SettingImpl<List<String>>) settingBuilder( "setting.depending.name", listOf( STRING ) )
                .addConstraint( dependency( SettingConstraints.size( 2 ), SettingConstraints.size( 4 ) , enumSetting, is( Colors.BLUE ) ) ).build();
        var dependencySetting2 = (SettingImpl<Integer>) settingBuilder( "setting.depending.name", INT )
                .addConstraint( dependency( max( 3 ), max(7), intSetting, min( 3 ) ) ).build();

        //Then
        assertEquals( "setting.name, a long which is power of 2", oneConstraintSetting.description() );
        assertEquals( "setting.name, an integer which is minimum `2` and is maximum `10`", twoConstraintSetting.description() );
        assertEquals( "setting.depending.name, a ',' separated list with elements of type 'a string'. which depends on setting.name." +
                " If setting.name is `BLUE` then it is of size `2` otherwise it is of size `4`.", dependencySetting1.description() );
        assertEquals( "setting.depending.name, an integer which depends on setting.name." +
                " If setting.name is minimum `3` then it is maximum `3` otherwise it is maximum `7`.", dependencySetting2.description() );
    }

    @TestFactory
    Collection<DynamicTest> testDescriptionDependency()
    {
        Collection<DynamicTest> tests = new ArrayList<>();
        tests.add( dynamicTest( "Test int dependency description",
                () -> testDescDependency( INT, "setting.child, an integer. If unset the value is inherited from setting.parent" ) ) );
        tests.add( dynamicTest( "Test socket dependency description", () -> testDescDependency( SOCKET_ADDRESS,
                "setting.child, a socket address. If missing port or hostname it is acquired from setting.parent" ) ) );
        tests.add( dynamicTest( "Test path dependency description",
                () -> testDescDependency( PATH, "setting.child, a path. If relative it is resolved from setting.parent" ) ) );
        return tests;
    }

    private static <T> void testDescDependency( SettingValueParser<T> parser, String expectedDescription )
    {
        var parent = settingBuilder( "setting.parent", parser ).immutable().build();
        var child = settingBuilder( "setting.child", parser ).setDependency( parent ).build();

        assertEquals( expectedDescription, child.description() );
    }

    private static <T> SettingImpl.Builder<T> settingBuilder( String name, SettingValueParser<T> parser )
    {
        return SettingImpl.newBuilder( name, parser, null );
    }

    private static <T> SettingImpl<T> setting( String name, SettingValueParser<T> parser )
    {
        return (SettingImpl<T>) SettingImpl.newBuilder( name, parser, null ).build();
    }

    private enum Colors
    {
        BLUE, GREEN, RED;
    }
    private enum StringEnum
    {
        DEFAULT( "default" ), V_1( "1.0" ), V_1_1( "1.1" );

        private final String name;
        StringEnum( String name )
        {
            this.name = name;
        }

        @Override
        public String toString()
        {
            return name;
        }
    }
}
