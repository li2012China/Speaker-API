package net.speakerapi.core;

/**
 * 听者（摄像机）位姿快照：由版本适配层从玩家摄像机取出后交给 common，
 * 用于定位语音的左右声像与距离衰减计算。
 *
 * @param position 听者位置
 * @param forward 摄像机前向单位向量（含俯仰）
 */
public record ListenerPose(Pos3 position, Pos3 forward) {
}
