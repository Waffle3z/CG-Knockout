import { GraphicEntityModule } from './entity-module/GraphicEntityModule.js';
import { EndScreenModule } from './endscreen-module/EndScreenModule.js';
import { RingModule, settings, setSetting } from './ring-module/RingModule.js';

// The ring module draws the whole game itself (procedural canvas art). The entity module is
// listed for the SDK's own pieces (end screen); the referee creates no entities.
export const modules = [
	GraphicEntityModule,
	RingModule,
	EndScreenModule
];

// Settings menu entries (the gear in the player). The SDK reads `options` from this file.
export const options = [
	{
		title: 'DEBUG VIEW',
		get: () => settings.debug,
		set: value => setSetting('debug', value),
		values: { 'OFF': false, 'ON': true }
	},
	{
		title: 'THEME',
		get: () => settings.dark,
		set: value => setSetting('dark', value),
		values: { 'LIGHT': false, 'DARK': true }
	}
];
